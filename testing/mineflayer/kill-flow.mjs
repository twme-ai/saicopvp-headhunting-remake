import mineflayer from 'mineflayer'

const bot = mineflayer.createBot({
  host: process.env.MINECRAFT_HOST ?? '127.0.0.1',
  port: Number(process.env.MINECRAFT_PORT ?? '25565'),
  username: process.env.MINECRAFT_USERNAME ?? 'HeadTest',
  auth: 'offline',
  version: '1.21.11'
})

const timeoutMs = 20_000
const messages = []

bot.on('message', message => {
  const text = message.toString()
  messages.push(text)
  process.stdout.write(`[message] ${text}\n`)
})

function delay(milliseconds) {
  return new Promise(resolve => setTimeout(resolve, milliseconds))
}

function waitForMessage(pattern) {
  const existing = messages.find(message => pattern.test(message))
  if (existing) return Promise.resolve(existing)
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      bot.removeListener('message', listener)
      reject(new Error(`Timed out waiting for message ${pattern}`))
    }, timeoutMs)
    const listener = message => {
      const text = message.toString()
      if (pattern.test(text)) {
        clearTimeout(timer)
        bot.removeListener('message', listener)
        resolve(text)
      }
    }
    bot.on('message', listener)
  })
}

async function waitForPig() {
  for (let attempt = 0; attempt < 20; attempt += 1) {
    const pig = Object.values(bot.entities).find(entity => entity.name === 'pig')
    if (pig) return pig
    await delay(250)
  }
  throw new Error('Spawn egg did not create a pig')
}

async function run() {
  await new Promise(resolve => bot.once('spawn', resolve))
  bot.chat('/kill @e[type=minecraft:pig]')
  await delay(500)
  bot.chat('/summon minecraft:pig ~ ~ ~')
  await waitForPig()
  bot.chat(`/damage @e[type=minecraft:pig,limit=1,sort=nearest] 100 minecraft:player_attack by ${bot.username}`)

  await waitForMessage(/Pig dropped an authentic head/)
  await delay(2_000)
  bot.chat('/headhunt sell all')
  await waitForMessage(/Sold 1 heads for \$10\.00/)
  bot.chat('/headhunt status')
  await waitForMessage(/11\/128 progress/)

  process.stdout.write('[result] PASS\n')
  bot.quit('Kill flow complete')
}

run().catch(error => {
  process.stderr.write(`[result] FAIL: ${error.stack ?? error}\n`)
  bot.quit('Kill flow failed')
  process.exitCode = 1
})
