package net.yogstation.yogbot.http

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import discord4j.common.util.Snowflake
import discord4j.core.GatewayDiscordClient
import discord4j.core.`object`.entity.Member
import net.yogstation.yogbot.DatabaseManager
import net.yogstation.yogbot.config.DiscordConfig
import net.yogstation.yogbot.config.HttpConfig
import net.yogstation.yogbot.util.StringUtils
import org.apache.commons.text.StringEscapeUtils
import org.slf4j.LoggerFactory
import org.springframework.http.HttpEntity
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.sql.SQLException
import java.util.*

@RestController
class VerificationController(
	private val webClient: WebClient,
	private val mapper: ObjectMapper,
	private val discordConfig: DiscordConfig,
	private val httpConfig: HttpConfig,
	private val database: DatabaseManager,
	private val client: GatewayDiscordClient
) {
	val oauthState: MutableMap<String, AuthIdentity> = HashMap()
	private val random = SecureRandom()

	@GetMapping("/api/verify")
	fun doRedirect(@RequestParam(value = "state") state: String): ResponseEntity<*> {
		if (discordConfig.oauthClientId == "") return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(hydrateError("Verification is not implemented"))
		val urlBuilder = StringBuilder(discordConfig.oauthAuthorizeUrl).apply {
			append("?response_type=code")
			append("&client_id=").append(discordConfig.oauthClientId)
			append("&redirect_uri=").append(httpConfig.publicPath).append("api/callback")
			append("&scope=openid")
			append("&state=").append(state)
		}
		return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(urlBuilder.toString())).build<Void>()
	}

	@PostMapping(value = ["/api/callback"], consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE])
	fun callbackPost(data: CallbackData): HttpEntity<String> {
		val state = data.state
		val csrfToken = data.csrftoken
		if (!oauthState.containsKey(state)) return HttpEntity(hydrateError(String.format("State %s is unknown", state)))
		val authIdentity: AuthIdentity = oauthState[state]
			?: return HttpEntity(hydrateError(String.format("State %s is unknown", state)))
		if (authIdentity.csrfToken == null || authIdentity.csrfToken != csrfToken) return HttpEntity(hydrateError("CSRF token mismatch"))
		oauthState.remove(state)

		try {
			database.connection.use { connection ->
				connection.prepareStatement(
					String.format(
						"SELECT discord_id FROM `%s` WHERE `ckey` = ?",
						database.prefix("player")
					)
				).use { queryStmt ->
					connection.prepareStatement(
						String.format(
							"UPDATE `%s` SET `discord_id` = ? WHERE `ckey` = ?",
							database.prefix("player")
						)
					).use { linkStmt ->
						queryStmt.setString(1, authIdentity.ckey)
						val queryResults = queryStmt.executeQuery()
						if (!queryResults.next()) return HttpEntity(hydrateError("New account detected, please login on the server at least once to proceed"))
						queryResults.close()
						linkStmt.setString(1, authIdentity.snowflake.asString())
						linkStmt.setString(2, authIdentity.ckey)
						linkStmt.execute()
						if (linkStmt.updateCount < 1) return HttpEntity(hydrateError("Failed to link accounts!"))
						client.getMemberById(Snowflake.of(discordConfig.mainGuildID), authIdentity.snowflake)
							.flatMap { member: Member -> member.addRole(Snowflake.of(discordConfig.byondVerificationRole)) }
							.subscribe()
						return HttpEntity(hydrateComplete())
					}
				}
			}
		} catch (e: SQLException) {
			LOGGER.error("Error linking accounts", e)
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(hydrateError("An error has occurred"))
		}
	}

	@GetMapping("/api/callback")
	fun getCallback(
		@RequestParam(value = "error", required = false) error: String?,
		@RequestParam(value = "error_description", required = false) errorDescription: String?,
		@RequestParam(value = "state", required = false) state: String?,
		@RequestParam(value = "code", required = false) code: String?
	): Mono<HttpEntity<String>> {
		if(discordConfig.oauthClientId == "") return Mono.just(HttpEntity(hydrateError("Verification is not implemented")))
		if (error != null) return Mono.just(
			HttpEntity(
				hydrateError(
					"Upstream login error: ${
						errorDescription
							?: error
					}"
				)
			)
		)
		if (state == null || code == null) return Mono.just(HttpEntity(hydrateError("State and code are both required")))
		val response = ResponseEntity.status(HttpStatus.OK).header("X-Frame-Options", "DENY")
		if (!oauthState.containsKey(state)) return Mono.just(response.body(hydrateError("State is unknown")))
		val identity = oauthState[state] ?: return Mono.just(response.body(hydrateError("State is unknown")))
		if (identity.csrfToken != null) return Mono.just(response.body(hydrateError("Authorization request already used")))
		val bodyValues: MultiValueMap<String, String> = LinkedMultiValueMap()
		bodyValues.add("grant_type", "authorization_code")
		bodyValues.add("code", code)
		bodyValues.add("redirect_uri", "${httpConfig.publicPath}api/callback")

		return webClient.post().uri(URI.create(discordConfig.oauthTokenUrl))
			.headers { headers -> headers.setBasicAuth(discordConfig.oauthClientId, discordConfig.oauthClientSecret) }
			.contentType(MediaType.APPLICATION_FORM_URLENCODED).body(BodyInserters.fromFormData(bodyValues)).retrieve()
			.bodyToMono(String::class.java).flatMap { token: String -> useToken(token, response, identity, state) }
	}

	private fun useToken(
		token: String,
		response: ResponseEntity.BodyBuilder,
		identity: AuthIdentity,
		state: String
	): Mono<HttpEntity<String>> {
		val accessToken: String = try {
			val root = mapper.readTree(token)
			val errorNode = root["error"]
			if (errorNode != null) {
				val errorDescriptionNode = root["error_description"]
				return Mono.just(
					response.body(
						hydrateError(
							String.format(
								"Upstream error when fetching access token: %s",
								if (errorDescriptionNode == null) errorNode.asText() else errorDescriptionNode.asText()
							)
						)
					)
				)
			}
			root["access_token"].asText()
		} catch (e: IOException) {
			LOGGER.error("Error getting token", e)
			return Mono.just(response.body(hydrateError("An error occurred while fetching access token")))
		}
		return webClient.get().uri(URI.create(discordConfig.oauthUserInfoUrl))
			.headers { headers -> headers.setBearerAuth(accessToken) }
			.retrieve().toEntity(String::class.java)
			.flatMap { ckeyResponseEntity: ResponseEntity<String?> ->
				if (!ckeyResponseEntity.statusCode.is2xxSuccessful || ckeyResponseEntity.body == null) return@flatMap Mono.just(
					response.body("Invalid access token when fetching user info")
				)
				val ckey: String = try {
					StringUtils.ckeyIze(mapper.readTree(ckeyResponseEntity.body)["ckey"].asText())
				} catch (e: JsonProcessingException) {
					LOGGER.info("Error processing info response", e)
					return@flatMap Mono.just(response.body(hydrateError("Failed to parse API response")))
				}
				if (ckey != identity.ckey) {
					return@flatMap Mono.just(
						response.body(
							hydrateError(
								String.format(
									"Ckey does not match, you attempted to login using %s while the linking process was initialized with %s",
									ckey,
									identity.ckey
								)
							)
						)
					)
				}
				val bytes = ByteArray(32)
				random.nextBytes(bytes)
				identity.csrfToken = StringUtils.bytesToHex(bytes)
				Mono.just(response.body(hydrateConfirm(identity.tag, identity.avatar, state, identity.csrfToken)))
			}
	}

	private fun hydrateError(error: String): String {
		return errorTpl.replace("\$errormsg$", StringEscapeUtils.escapeHtml4(error))
	}

	private fun hydrateComplete(): String {
		return completeTpl
	}

	private fun hydrateConfirm(
		rawDiscordTag: String,
		rawDiscordAvatar: String,
		rawState: String,
		rawCsrfToken: String?
	): String {
		val discordTag = StringEscapeUtils.escapeHtml4(rawDiscordTag)
		val discordAvatar = StringEscapeUtils.escapeHtml4(rawDiscordAvatar)
		val state = StringEscapeUtils.escapeHtml4(rawState)
		val csrfToken = StringEscapeUtils.escapeHtml4(rawCsrfToken)
		return confirmTpl.replace("\\\$usertag\\$".toRegex(), discordTag)
			.replace("\\\$useravatar\\$".toRegex(), discordAvatar).replace("\\\$state\\$".toRegex(), state)
			.replace("\\\$csrftoken\\$".toRegex(), csrfToken)
	}

	class AuthIdentity(var ckey: String, var snowflake: Snowflake, var avatar: String, var tag: String) {
		var csrfToken: String? = null
	}

	companion object {
		private const val errorTpl =
			"`<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, shrink-to-fit=no\"><title>Yogstation Account Linking</title><link rel=\"stylesheet\" href=\"https://cdn.jsdelivr.net/npm/bootstrap@5.1.3/dist/css/bootstrap.min.css\"></head><body><header class=\"d-flex justify-content-center align-items-center\" style=\"height: 3rem;background: var(--bs-blue);\"><h1 style=\"color: rgb(255,255,255);\">Yogstation Account Linking</h1></header><div class=\"container\" style=\"margin-top: 2rem;\"><div class=\"card\"><div class=\"card-body d-flex flex-column align-items-center\"><h4 class=\"card-title\">An error occured!</h4><p class=\"card-text\">\$errormsg$</p></div></div></div><script src=\"https://cdn.jsdelivr.net/npm/bootstrap@5.1.3/dist/js/bootstrap.bundle.min.js\"></script></body></html>`"
		private const val confirmTpl =
			"<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, shrink-to-fit=no\"><title>Yogstation Account Linking</title><link rel=\"stylesheet\" href=\"https://cdn.jsdelivr.net/npm/bootstrap@5.1.3/dist/css/bootstrap.min.css\"></head><body><header class=\"d-flex justify-content-center align-items-center\" style=\"height: 3rem;background: var(--bs-blue);\"><h1 style=\"color: rgb(255,255,255);\">Yogstation Account Linking</h1></header><div class=\"container\" style=\"margin-top: 2rem;\"><div class=\"card\"><div class=\"card-body d-flex flex-column justify-content-center align-items-center\"><h4 class=\"card-title\">Please confirm account linking</h4><p class=\"card-text\">Is this your discord account?</p><span>\$usertag$</span><img class=\"rounded-circle d-block\" src=\"\$useravatar$\" style=\"margin: 0 auto;\"><form style=\"margin-top: 2rem;\" method=\"post\"><input class=\"form-control\" type=\"hidden\" value=\"\$csrftoken$\" name=\"csrftoken\"><input class=\"form-control\" type=\"hidden\" name=\"state\" value=\"\$state$\"><button class=\"btn btn-danger\" type=\"button\" style=\"margin: 0 1rem;\" onclick=\"window.close()\">No</button><button class=\"btn btn-success\" type=\"submit\" style=\"margin: 0 1rem;\">Yes</button></form></div></div></div><script src=\"https://cdn.jsdelivr.net/npm/bootstrap@5.1.3/dist/js/bootstrap.bundle.min.js\"></script></body></html>"
		private const val completeTpl =
			"<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, shrink-to-fit=no\"><title>Yogstation Account Linking</title><link rel=\"stylesheet\" href=\"https://cdn.jsdelivr.net/npm/bootstrap@5.1.3/dist/css/bootstrap.min.css\"></head><body><header class=\"d-flex justify-content-center align-items-center\" style=\"height: 3rem;background: var(--bs-blue);\"><h1 style=\"color: rgb(255,255,255);\">Yogstation Account Linking</h1></header><div class=\"container\" style=\"margin-top: 2rem;\"><div class=\"card\"><div class=\"card-body d-flex flex-column align-items-center\"><h4 class=\"card-title\">Your account is now linked!</h4></div></div></div><script src=\"https://cdn.jsdelivr.net/npm/bootstrap@5.1.3/dist/js/bootstrap.bundle.min.js\"></script></body></html>"
		private val LOGGER = LoggerFactory.getLogger(VerificationController::class.java)
	}
}