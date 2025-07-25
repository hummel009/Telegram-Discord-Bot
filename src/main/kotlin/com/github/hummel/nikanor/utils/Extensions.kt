package com.github.hummel.nikanor.utils

import com.github.hummel.nikanor.bean.GuildData
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.MessageEmbed
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

fun EmbedBuilder.error(member: Member?, guildData: GuildData, desc: String): MessageEmbed = apply {
	member ?: return@apply

	setAuthor(member.effectiveName, null, member.effectiveAvatarUrl)
	setTitle(I18n.of("title_error", guildData))
	setDescription(desc)
	setColor(0xFF0000)
}.build()

fun EmbedBuilder.access(member: Member?, guildData: GuildData, desc: String): MessageEmbed = apply {
	member ?: return@apply

	setAuthor(member.effectiveName, null, member.effectiveAvatarUrl)
	setTitle(I18n.of("title_access", guildData))
	setDescription(desc)
	setColor(0xFFFF00)
}.build()

fun EmbedBuilder.success(member: Member?, guildData: GuildData, desc: String): MessageEmbed = apply {
	member ?: return@apply

	setAuthor(member.effectiveName, null, member.effectiveAvatarUrl)
	setTitle(I18n.of("title_success", guildData))
	setDescription(desc)
	setColor(0x00FF00)
}.build()

fun ByteArray.resizeImage(width: Int): ByteArray {
	val inputStream = ByteArrayInputStream(this)
	val originalImage = ImageIO.read(inputStream)

	val originalWidth = originalImage.width
	val originalHeight = originalImage.height

	val newWidth = width
	val newHeight = (originalHeight.toDouble() / originalWidth.toDouble() * newWidth).toInt()

	val resizedImage = BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_ARGB)
	val graphics2D = resizedImage.createGraphics()
	graphics2D.drawImage(originalImage.getScaledInstance(newWidth, newHeight, Image.SCALE_SMOOTH), 0, 0, null)
	graphics2D.dispose()

	val outputStream = ByteArrayOutputStream()
	ImageIO.write(resizedImage, "webp", outputStream)
	return outputStream.toByteArray()
}