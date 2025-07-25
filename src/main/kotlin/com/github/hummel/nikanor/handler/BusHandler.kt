package com.github.hummel.nikanor.handler

import com.github.hummel.nikanor.ApiHolder
import com.github.hummel.nikanor.bean.BotData
import com.github.hummel.nikanor.factory.ServiceFactory
import com.github.hummel.nikanor.service.DataService
import com.github.hummel.nikanor.utils.resizeImage
import net.dv8tion.jda.api.events.GenericEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.hooks.EventListener
import net.dv8tion.jda.api.utils.FileProxy
import net.dv8tion.jda.api.utils.FileUpload
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer
import org.telegram.telegrambots.meta.api.methods.GetFile
import org.telegram.telegrambots.meta.api.methods.send.*
import org.telegram.telegrambots.meta.api.objects.InputFile
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.media.InputMediaAudio
import org.telegram.telegrambots.meta.api.objects.media.InputMediaDocument
import org.telegram.telegrambots.meta.api.objects.media.InputMediaPhoto
import org.telegram.telegrambots.meta.api.objects.media.InputMediaVideo
import java.io.File
import java.net.URL
import java.nio.file.Files

object BusHandler : EventListener, LongPollingSingleThreadUpdateConsumer {
	private val dataService: DataService = ServiceFactory.dataService

	override fun onEvent(event: GenericEvent) {
		if (event is MessageReceivedEvent) {
			if (event.author.isBot) {
				return
			}

			val discordChannelId = event.channel.idLong
			val busRegistry = dataService.loadBusRegistry()
			if (!busRegistry.discordToTelegram.contains(discordChannelId)) {
				return
			}
			val telegramChatId = busRegistry.discordToTelegram[discordChannelId]!!

			Bridge.transferToTelegram(event, telegramChatId)
		}
	}

	override fun consume(update: Update) {
		if (update.hasMessage()) {
			if (update.message.from.isBot) {
				return
			}

			val telegramChatId = update.message.chatId
			val busRegistry = dataService.loadBusRegistry()
			if (!busRegistry.telegramToDiscord.contains(telegramChatId)) {
				return
			}
			val discordChannelId = busRegistry.telegramToDiscord[telegramChatId]!!

			Bridge.transferToDiscord(update, discordChannelId)
		}
	}

	object Bridge {
		fun transferToTelegram(event: MessageReceivedEvent, telegramChatId: Long) {
			try {
				val reference = event.message.referencedMessage
				val referenceId = reference?.contentDisplay?.takeIf {
					it.contains("[&") && it.contains("]")
				}?.substringAfter("[&")?.substringBefore("]")

				val ownSide = reference != null && referenceId == null

				val message = buildString {
					val content = event.message.contentDisplay
					val author = with(event.message.author.effectiveName) {
						replace("  ", " ").replace(" ", "_")
					}
					val answer = if (ownSide) {
						val maxLength = 30
						val originalText = reference.contentDisplay
						val displayText = if (originalText.length > maxLength) {
							originalText.substring(0, maxLength) + "..."
						} else {
							originalText
						}
						if (displayText.isEmpty()) {
							""
						} else {
							" ➦ «$displayText»"
						}
					} else ""
					val id = "\r\n[&${event.message.idLong}]"
					val separator = if (content.contains("[\n\r]".toRegex())) "\n\n" else " "

					append("#")
					append(author)
					append(answer)
					append(":")
					append(separator)
					append(content)
					append(id)
				}

				ApiHolder.telegram.execute(SendMessage.builder().apply {
					chatId(telegramChatId)
					text(message)
					referenceId?.let { replyToMessageId(it.toInt()) }
				}.build())

				val attachments = event.message.attachments
				val stickers = event.message.stickers
				if (attachments.isEmpty() && stickers.isEmpty()) {
					return
				}

				val images = mutableListOf<File>()
				val videos = mutableListOf<File>()
				val audios = mutableListOf<File>()
				val gifs = mutableListOf<File>()
				val documents = mutableListOf<File>()

				val tempDir = Files.createTempDirectory("discord_attachments_")
				val tempFiles = mutableListOf<File>()

				try {
					for (attachment in attachments) {
						if (attachment.size >= 5_000_000) {
							continue
						}
						val byteArray = FileProxy(attachment.url).download().join().readBytes()
						val tempFile = tempDir.resolve(attachment.fileName).toFile()
						tempFile.writeBytes(byteArray)
						tempFiles.add(tempFile)

						when (attachment.fileExtension?.lowercase()) {
							"jpg", "jpeg", "png" -> images.add(tempFile)
							"mp4", "mov", "mpg", "mpeg" -> videos.add(tempFile)
							"mp3", "wav", "ogg", "m4a" -> audios.add(tempFile)
							"gif" -> gifs.add(tempFile)
							else -> documents.add(tempFile)
						}
					}
					for (sticker in stickers) {
						val url = sticker.iconUrl
						if (url.contains(".json")) {
							continue
						}

						val byteArray = URL(url).readBytes()
						val extension = url.substringAfterLast('.', "").lowercase()
						val fileName = "${sticker.id}.$extension"
						val tempFile = tempDir.resolve(fileName).toFile()
						tempFile.writeBytes(byteArray)
						tempFiles.add(tempFile)

						when (extension) {
							"jpg", "jpeg", "png" -> images.add(tempFile)
							"gif" -> gifs.add(tempFile)
						}
					}

					if (images.size > 1) {
						ApiHolder.telegram.execute(SendMediaGroup.builder().apply {
							chatId(telegramChatId.toString())
							medias(images.map {
								InputMediaPhoto(it, it.name)
							})
						}.build())
					} else if (images.size == 1) {
						ApiHolder.telegram.execute(SendPhoto.builder().apply {
							chatId(telegramChatId)
							photo(InputFile(images[0]))
						}.build())
					}

					if (videos.size > 1) {
						ApiHolder.telegram.execute(SendMediaGroup.builder().apply {
							chatId(telegramChatId.toString())
							medias(videos.map {
								InputMediaVideo(it, it.name)
							})
						}.build())
					} else if (videos.size == 1) {
						ApiHolder.telegram.execute(SendVideo.builder().apply {
							chatId(telegramChatId)
							video(InputFile(videos[0]))
						}.build())
					}

					if (audios.size > 1) {
						ApiHolder.telegram.execute(SendMediaGroup.builder().apply {
							chatId(telegramChatId.toString())
							medias(audios.map {
								InputMediaAudio(it, it.name)
							})
						}.build())
					} else if (audios.size == 1) {
						ApiHolder.telegram.execute(SendAudio.builder().apply {
							chatId(telegramChatId)
							audio(InputFile(audios[0]))
						}.build())
					}

					if (documents.size > 1) {
						ApiHolder.telegram.execute(SendMediaGroup.builder().apply {
							chatId(telegramChatId.toString())
							medias(documents.map {
								InputMediaDocument(it, it.name)
							})
						}.build())
					} else if (documents.size == 1) {
						ApiHolder.telegram.execute(SendDocument.builder().apply {
							chatId(telegramChatId)
							document(InputFile(documents[0]))
						}.build())
					}

					for (inputFile in gifs) {
						ApiHolder.telegram.execute(SendAnimation.builder().apply {
							chatId(telegramChatId)
							animation(InputFile(inputFile))
						}.build())
					}
				} catch (ex: Exception) {
					ex.printStackTrace()
				} finally {
					tempFiles.forEach { it.delete() }
					tempDir.toFile().delete()
				}
			} catch (e: Exception) {
				e.printStackTrace()
			}
		}

		fun transferToDiscord(update: Update, discordChannelId: Long) {
			try {
				val reply = update.message.replyToMessage
				val replyId = (reply?.text ?: reply?.caption)?.takeIf {
					it.contains("[&") && it.contains("]")
				}?.substringAfter("[&")?.substringBefore("]")

				val ownSide = reply != null && replyId == null

				val message = buildString {
					val content = update.message.text ?: update.message.caption ?: ""
					val author = (update.message.from.userName ?: listOfNotNull(
						update.message.from.firstName, update.message.from.lastName
					).joinToString("_")).replace("\\s+".toRegex(), "_")
					val answer = if (ownSide) {
						val maxLength = 30
						val originalText = reply.text ?: reply.caption ?: ""
						val displayText = if (originalText.length > maxLength) {
							originalText.substring(0, maxLength) + "..."
						} else {
							originalText
						}
						if (displayText.isEmpty()) {
							""
						} else {
							" ➦ «$displayText»"
						}
					} else ""
					val id = "\r\n-# [&${update.message.messageId}]"
					val separator = if (content.contains("[\n\r]".toRegex())) "\n\n" else " "

					append("__#")
					append(author)
					append("__")
					append(answer)
					append(":")
					append(separator)
					append(content)
					append(id)
				}

				val channel = ApiHolder.discord.getTextChannelById(
					discordChannelId
				) ?: ApiHolder.discord.getThreadChannelById(
					discordChannelId
				) ?: return

				fun sendFile(fileId: String, fileName: String, isImageAndResize: Boolean = false) {
					val url = ApiHolder.telegram.execute(GetFile(fileId)).getFileUrl(BotData.telegramToken)
					val byteArray = URL(url).readBytes()
					val result = if (isImageAndResize) byteArray.resizeImage(160) else byteArray
					channel.sendMessage(message).apply {
						addFiles(FileUpload.fromData(result, fileName))
						replyId?.let { setMessageReference(it.toLong()) }
						queue()
					}
				}

				when {
					update.message.photo != null -> {
						val photoSize = update.message.photo.last()
						sendFile(photoSize.fileId, "photo.jpg")
					}

					update.message.video != null -> {
						val video = update.message.video
						sendFile(video.fileId, video.fileName ?: "video.mp4")
					}

					update.message.audio != null -> {
						val audio = update.message.audio
						sendFile(audio.fileId, audio.fileName ?: "audio.mp3")
					}

					update.message.document != null -> {
						val file = update.message.document
						sendFile(file.fileId, file.fileName)
					}

					update.message.animation != null -> {
						val animation = update.message.animation
						sendFile(animation.fileId, animation.fileName ?: "animation.gif")
					}

					update.message.voice != null -> {
						val voice = update.message.voice
						sendFile(voice.fileId, "voice.ogg")
					}

					update.message.sticker != null -> {
						val sticker = update.message.sticker
						sendFile(sticker.fileId, sticker.fileUniqueId + ".webp", true)
					}

					else -> {
						channel.sendMessage(message).apply {
							replyId?.let { setMessageReference(it.toLong()) }
							queue()
						}
					}
				}
			} catch (e: Exception) {
				e.printStackTrace()
			}
		}
	}
}