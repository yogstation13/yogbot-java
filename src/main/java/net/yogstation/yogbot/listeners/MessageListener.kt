package net.yogstation.yogbot.listeners

import discord4j.common.util.Snowflake
import discord4j.core.GatewayDiscordClient
import discord4j.core.event.domain.message.MessageCreateEvent
import net.yogstation.yogbot.GithubManager
import net.yogstation.yogbot.config.DiscordConfig
import net.yogstation.yogbot.util.DiscordUtil
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import java.util.regex.*

@Component
class MessageListener(
	client: GatewayDiscordClient,
	val discordConfig: DiscordConfig,
	private val githubManager: GithubManager
) {
	private val prPattern: Pattern = Pattern.compile("\\[(?:(?<org>[\\w.-]+)/)?(?<repo>[\\w.-]+)?#(?<id>\\d+)]")

	init {
		client.on(MessageCreateEvent::class.java) { event: MessageCreateEvent -> handle(event) }.subscribe()
	}

	fun handle(event: MessageCreateEvent): Mono<*> {
		var responses: Mono<*> = Mono.empty<Any>()

		val content: String = event.message.content
		if (event.message.author.isEmpty) return responses

		if (content.contains("snail", ignoreCase = true) && content.contains("when", ignoreCase = true))
			responses = responses.and(DiscordUtil.reply(event, "When you code it"))

		val jesterRole = Snowflake.of(discordConfig.jesterRole)
		if (event.message.roleMentionIds.contains(jesterRole)) {
			responses = responses.and(
				event.message.authorAsMember
					.filter { member -> !member.roleIds.contains(jesterRole) }
					.flatMap { member -> member.addRole(jesterRole) }
					.and(
						DiscordUtil.reply(
							event,
							"It appears you have, for the first time, engaged in the dastardly action to ping " +
								"Jester! For this crime you have been assigned the role of Jester. " +
								"Congratulations on your promotion!"
						)
					)
			)
		}

		val prMatcher: Matcher = prPattern.matcher(content)
		if (prMatcher.find()) {
			responses = responses.and(
				event.message.channel.flatMap { channel ->
					githubManager.postPR(
						channel,
						prMatcher.group("org") ?: "yogstation13",
						prMatcher.group("repo") ?: "Yogstation",
						prMatcher.group("id")
					)
				}
			)
		}

		return responses
	}
}
