import mineflayer from 'mineflayer'

const bot = mineflayer.createBot({
  host: process.env.MINECRAFT_HOST ?? '127.0.0.1',
  port: Number(process.env.MINECRAFT_PORT ?? '25565'),
  username: process.env.MINECRAFT_USERNAME ?? 'HeadTest',
  auth: 'offline',
  version: '1.21.11'
})

const timeout = setTimeout(() => {
  process.stderr.write('[result] FAIL: timed out waiting for persisted status\n')
  bot.quit('Persistence test timed out')
  process.exitCode = 1
}, 15_000)

bot.once('spawn', () => bot.chat('/headhunt status'))

let foundBalance = false
let foundProgress = false
bot.on('message', message => {
  const text = message.toString()
  process.stdout.write(`[message] ${text}\n`)
  foundBalance ||= /Balance: \$100\.00/.test(text)
  foundProgress ||= /10\/128 progress/.test(text)
  if (foundBalance && foundProgress) {
    clearTimeout(timeout)
    process.stdout.write('[result] PASS\n')
    bot.quit('Persistence test complete')
  }
})

bot.on('error', error => {
  clearTimeout(timeout)
  process.stderr.write(`[result] FAIL: ${error.stack ?? error}\n`)
  process.exitCode = 1
})
