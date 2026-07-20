import mineflayer from 'mineflayer'

const host = process.env.MINECRAFT_HOST ?? '127.0.0.1'
const port = Number(process.env.MINECRAFT_PORT ?? '25565')
const username = process.env.MINECRAFT_USERNAME ?? 'HeadTest'
const timeoutMs = 15_000

const bot = mineflayer.createBot({ host, port, username, auth: 'offline', version: '1.21.11' })
const messages = []

bot.on('message', message => {
  const text = message.toString()
  messages.push(text)
  process.stdout.write(`[message] ${text}\n`)
})

bot.on('error', error => {
  process.stderr.write(`[error] ${error.stack ?? error}\n`)
})

function delay(milliseconds) {
  return new Promise(resolve => setTimeout(resolve, milliseconds))
}

function waitForMessage(pattern, timeout = timeoutMs) {
  const existing = messages.find(message => pattern.test(message))
  if (existing) return Promise.resolve(existing)
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      bot.removeListener('message', listener)
      reject(new Error(`Timed out waiting for message ${pattern}`))
    }, timeout)
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

function waitForWindow(timeout = timeoutMs) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      bot.removeListener('windowOpen', listener)
      reject(new Error('Timed out waiting for inventory window'))
    }, timeout)
    const listener = window => {
      clearTimeout(timer)
      resolve(window)
    }
    bot.once('windowOpen', listener)
  })
}

async function run() {
  await new Promise(resolve => bot.once('spawn', resolve))
  process.stdout.write(`[spawn] ${username}\n`)

  bot.chat('/headhunt status')
  await waitForMessage(/Balance:/)

  bot.chat('/headhunt language zh_TW')
  await waitForMessage(/獵頭系統語言已切換為 zh_TW/)

  bot.chat('/headhunt language en_US')
  await waitForMessage(/HeadHunting language is now en_US/)

  const levelWindowPromise = waitForWindow()
  bot.chat('/level')
  const levelWindow = await levelWindowPromise
  process.stdout.write(`[window] level id=${levelWindow.id} slots=${levelWindow.slots.length}\n`)
  bot.closeWindow(levelWindow)

  const exchangeWindowPromise = waitForWindow()
  bot.chat('/headhunt exchange')
  const exchangeWindow = await exchangeWindowPromise
  process.stdout.write(`[window] exchange id=${exchangeWindow.id} slots=${exchangeWindow.slots.length}\n`)
  bot.closeWindow(exchangeWindow)

  await delay(1_000)
  bot.chat(`/headhunt admin give ${username} pig 10`)
  await waitForMessage(/Minted 10 pig heads/)
  await delay(2_000)

  bot.chat('/headhunt sell all')
  await waitForMessage(/Sold 10 heads for/)

  bot.chat('/headhunt status')
  await waitForMessage(/10\/128/)

  bot.chat('/rankup')
  await waitForMessage(/still need 118 progress/)

  process.stdout.write('[result] PASS\n')
  bot.quit('Smoke test complete')
}

run().catch(error => {
  process.stderr.write(`[result] FAIL: ${error.stack ?? error}\n`)
  bot.quit('Smoke test failed')
  process.exitCode = 1
})
