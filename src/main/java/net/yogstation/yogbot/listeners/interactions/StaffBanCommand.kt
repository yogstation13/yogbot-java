package net.yogstation.yogbot.listeners.interactions

import discord4j.common.util.Snowflake
import discord4j.core.event.domain.interaction.ApplicationCommandInteractionEvent
import discord4j.core.event.domain.interaction.UserInteractionEvent
import discord4j.core.`object`.entity.Member
import discord4j.core.`object`.entity.User
import net.yogstation.yogbot.config.DiscordConfig
import net.yogstation.yogbot.data.StaffBanRepository
import net.yogstation.yogbot.data.entity.StaffBan
import net.yogstation.yogbot.permissions.PermissionsManager
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

@Component
class StaffBanCommand(
	private val permissions: PermissionsManager,
	private val discordConfig: DiscordConfig,
	private val staffBanRepository: StaffBanRepository
) :
	IUserCommand {
	override val name = "staffban"

	override val uri = "staffban.json"

	override fun handle(event: UserInteractionEvent): Mono<*> {
		if (event.interaction.guildId.isEmpty) return event.reply().withEphemeral(true)
			.withContent("Must be used in a guild")

		return if (!permissions.hasPermission(event.interaction.member.orElse(null), "staffban")) event.reply()
			.withEphemeral(true).withContent("You do not have permission to run that command")
		else event.targetUser
			.flatMap { user: User ->
				user.asMember(event.interaction.guildId.get()).flatMap { member: Member ->
					staffBan(member, event)
				}
			}
	}

	fun staffBan(
		member: Member,
		event: ApplicationCommandInteractionEvent
	): Mono<Void> {
		val roles = member.roleIds
		return if (roles.contains(Snowflake.of(discordConfig.secondWarningRole))) {
			Mono.fromRunnable<StaffBan>{
				staffBanRepository.save(StaffBan(member.id.asLong()))
			}.subscribeOn(Schedulers.boundedElastic()).subscribe()
			member.addRole(Snowflake.of(discordConfig.staffPublicBanRole))
				.then(event.reply().withContent("${member.mention} was banned from staff public"))
		} else if (roles.contains(Snowflake.of(discordConfig.firstWarningRole))) {
			member.addRole(Snowflake.of(discordConfig.secondWarningRole))
				.then(event.reply().withContent("${member.mention} was given the second warning role"))
		} else {
			member.addRole(Snowflake.of(discordConfig.firstWarningRole))
				.then(event.reply().withContent("${member.mention} was given the first warning role"))
		}
	}
}
