package net.yogstation.yogbot.listeners.interactions.slash

import discord4j.common.util.Snowflake
import discord4j.core.`object`.command.ApplicationCommandInteractionOption
import discord4j.core.`object`.command.ApplicationCommandInteractionOptionValue
import discord4j.core.event.domain.interaction.ChatInputInteractionEvent
import net.yogstation.yogbot.listeners.interactions.ISlashCommand
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

@Component
class KickCommand: ISlashCommand {
	override val name = "kick"

	override fun handle(event: ChatInputInteractionEvent): Mono<*> {
		val userid: Snowflake = event.getOption("person").flatMap(ApplicationCommandInteractionOption::getValue)
			.map(ApplicationCommandInteractionOptionValue::asSnowflake).orElse(Snowflake.of(0))
		if(userid.asLong() == 0L) event.reply().withEphemeral(true).withContent("Failed to find person")

		return event.interaction.guild.flatMap { guild ->
			guild.getMemberById(userid).flatMap { member ->
				member.kick("Kicked by ${event.interaction.user.username}")
			}
		}
	}

	override val uri: String
		get() = "slash_kick.json"
}