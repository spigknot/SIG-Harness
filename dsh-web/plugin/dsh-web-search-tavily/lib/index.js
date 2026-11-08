/**
 * Provedor de busca Tavily para o seam `ctx.web` do DeepSeek Harness.
 *
 * O plugin registra um `WebSearchProvider` com id `tavily`. A seleção continua
 * sendo do `dsh-web`, que recusa dois provedores utilizáveis --- por isso a
 * linha `web` do profile fixa `searchProvider: tavily`.
 *
 * Sem imports de `@deepseek-ai/*`: um plugin instalado em
 * `~/.dsh/profiles/web/node_modules` não resolve os pacotes do DSH (eles vivem
 * na instalação, não no profile), então aqui só há builtins e o contexto
 * Cordis. O `WebError` do seam é reproduzido por forma (nome + `code`), que é o
 * que os chamadores roteiam.
 */

import { existsSync, readFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { join } from 'node:path'

/** Nome Cordis do plugin (diagnóstico do loader). */
export const name = 'web-search-tavily'

/** O seam em que este plugin se registra. */
export const inject = ['web']

/** Id estável do provedor na capability de busca. */
export const TAVILY_PROVIDER_ID = 'tavily'

const DEFAULT_BASE_URL = 'https://api.tavily.com'
const DEFAULT_API_KEY_ENV = 'TAVILY_API_KEY'
/** A API do Tavily aceita no máximo 20 resultados por busca. */
const TAVILY_MAX_RESULTS = 20
/** Config gravado pelo próprio CLI (`tvly login`), com permissão 0600. */
const CLI_CONFIG_PATH = join(homedir(), '.tavily', 'config.json')

/**
 * Erro no formato que o seam espera: `name: 'WebError'` e um `code` estável.
 * Local porque `@deepseek-ai/dsh-web` não é resolvível a partir do profile.
 */
class TavilyWebError extends Error {
  /**
   * @param {string} message - texto exibido ao chamador.
   * @param {string} code - código roteável do seam.
   * @param {{ cause?: unknown }} [options] - causa encadeada.
   */
  constructor(message, code, options) {
    super(message, options)
    this.name = 'WebError'
    this.code = code
  }
}

/**
 * Lê a chave que o CLI da Tavily guardou. Último recurso da resolução e nunca
 * lança: arquivo ausente ou inválido apenas não contribui.
 *
 * @returns {string | undefined} a chave, ou `undefined`.
 */
function readCliApiKey() {
  try {
    const parsed = JSON.parse(readFileSync(CLI_CONFIG_PATH, 'utf8'))
    const key = typeof parsed?.api_key === 'string' ? parsed.api_key.trim() : ''
    return key.length > 0 ? key : undefined
  } catch {
    return undefined
  }
}

/**
 * Normaliza a linha `config` do profile. Tudo é opcional: sem `apiKeyEnv` vale
 * `TAVILY_API_KEY`, sem `baseURL` vale o endpoint público.
 *
 * @param {Record<string, unknown> | undefined} config - a linha crua do loader.
 * @returns {{ apiKey?: string, apiKeyEnv: string, baseURL: string, searchDepth: 'basic' | 'advanced', includeAnswer: boolean }} opções preenchidas.
 */
function normalizeConfig(config) {
  const raw = config ?? {}
  const apiKey = typeof raw.apiKey === 'string' && raw.apiKey.trim().length > 0 ? raw.apiKey.trim() : undefined
  const apiKeyEnv =
    typeof raw.apiKeyEnv === 'string' && raw.apiKeyEnv.trim().length > 0 ? raw.apiKeyEnv.trim() : DEFAULT_API_KEY_ENV
  const baseURL =
    typeof raw.baseURL === 'string' && raw.baseURL.trim().length > 0
      ? raw.baseURL.trim().replace(/\/+$/, '')
      : DEFAULT_BASE_URL
  const searchDepth = raw.searchDepth === 'advanced' ? 'advanced' : 'basic'
  const includeAnswer = raw.includeAnswer === true
  return { ...(apiKey === undefined ? {} : { apiKey }), apiKeyEnv, baseURL, searchDepth, includeAnswer }
}

/**
 * Resolve a chave sem duplicar segredo: literal na config (evitada), cofre de
 * credenciais do DSH, ambiente de lançamento, `process.env` e, por fim, o
 * arquivo do CLI da Tavily.
 *
 * @param {import('@deepseek-ai/cordis').Context} ctx - contexto do plugin.
 * @param {{ apiKey?: string, apiKeyEnv: string }} options - configuração normalizada.
 * @returns {Promise<string | undefined>} a chave, ou `undefined` quando não há nenhuma.
 */
async function resolveApiKey(ctx, options) {
  if (options.apiKey !== undefined) return options.apiKey
  const credentials = ctx.get('credentials')
  if (credentials !== undefined && typeof credentials.resolve === 'function') {
    try {
      const hit = await credentials.resolve(options.apiKeyEnv)
      const value = typeof hit?.value === 'string' ? hit.value.trim() : ''
      if (value.length > 0) return value
    } catch {
      /* cofre indisponível: tenta as demais fontes */
    }
  }
  const ambient = ctx.get('launchEnvironment')?.get(options.apiKeyEnv)?.value
  if (typeof ambient === 'string' && ambient.trim().length > 0) return ambient.trim()
  const fromEnv = process.env[options.apiKeyEnv]
  if (typeof fromEnv === 'string' && fromEnv.trim().length > 0) return fromEnv.trim()
  return readCliApiKey()
}

/**
 * Extrai o motivo do corpo de erro para a falha citar o que a API disse, e não
 * apenas o status.
 *
 * @param {Response} response - resposta não-2xx.
 * @returns {Promise<string>} sufixo pronto para a mensagem, possivelmente vazio.
 */
async function describeFailure(response) {
  try {
    const text = (await response.text()).trim()
    if (text.length === 0) return ''
    return `: ${text.slice(0, 300)}`
  } catch {
    return ''
  }
}

/**
 * Converte o corpo do `/search` para a forma normalizada do seam, deduplicando
 * por URL e sem inventar campos que o provedor não mandou.
 *
 * @param {{ answer?: unknown, results?: unknown }} payload - corpo JSON da API.
 * @returns {{ content?: string, sources: Array<{ url: string, title?: string, snippet?: string, publishedAt?: string }>, truncated: boolean }} resultado normalizado.
 */
function mapSearchResponse(payload) {
  const rawResults = Array.isArray(payload?.results) ? payload.results : []
  const seen = new Set()
  const sources = []
  for (const item of rawResults) {
    const url = typeof item?.url === 'string' ? item.url.trim() : ''
    if (url.length === 0 || seen.has(url)) continue
    seen.add(url)
    sources.push({
      url,
      ...(typeof item.title === 'string' && item.title.length > 0 ? { title: item.title } : {}),
      ...(typeof item.content === 'string' && item.content.length > 0 ? { snippet: item.content } : {}),
      ...(typeof item.published_date === 'string' && item.published_date.length > 0
        ? { publishedAt: item.published_date }
        : {}),
    })
  }
  const answer = typeof payload?.answer === 'string' && payload.answer.trim().length > 0 ? payload.answer : undefined
  return { ...(answer === undefined ? {} : { content: answer }), sources, truncated: false }
}

/**
 * Qual fonte o provedor usaria agora. `describe` só conhece o cofre e o
 * ambiente, mas a busca ainda cai no arquivo do CLI --- reportar `configured`
 * sozinho faria a página dizer "sem chave" com a busca funcionando.
 *
 * @param {{ configured: boolean, source?: string }} info - estado do cofre.
 * @param {string} reference - nome da credencial.
 * @returns {string | null} `stored`, `environment`, `cli-config` ou `null`.
 */
function effectiveKeySource(info, reference) {
  if (info.configured === true) return info.source ?? 'stored'
  const ambient = process.env[reference]
  if (typeof ambient === 'string' && ambient.trim().length > 0) return 'environment'
  if (existsSync(CLI_CONFIG_PATH)) return 'cli-config'
  return null
}

/**
 * Estado da chave para a página de configuração, sem o valor.
 *
 * @param {object} credentials - serviço de credenciais.
 * @param {string} reference - nome da credencial.
 * @returns {Promise<{ configured: boolean, writable: boolean, source: string | null, effective: string | null }>} estado publicável.
 */
async function describeKeyState(credentials, reference) {
  const info = await credentials.describe(reference)
  return {
    configured: info.configured === true,
    writable: info.writable === true,
    source: info.source ?? null,
    effective: effectiveKeySource(info, reference),
  }
}

/** Teto do corpo aceito pela rota da chave; um segredo cabe folgado. */
const KEY_BODY_LIMIT = 4096

/**
 * Responde JSON encerrando a resposta.
 *
 * @param {import('node:http').ServerResponse} res - resposta da requisição.
 * @param {number} status - código HTTP.
 * @param {Record<string, unknown>} payload - corpo serializável.
 * @returns {void}
 */
function sendJson(res, status, payload) {
  res.writeHead(status, { 'content-type': 'application/json', 'cache-control': 'no-store' })
  res.end(JSON.stringify(payload))
}

/**
 * Lê e valida o corpo JSON da requisição, com teto de tamanho para não aceitar
 * envios ilimitados.
 *
 * @param {import('node:http').IncomingMessage} req - requisição recebida.
 * @returns {Promise<Record<string, unknown>>} o corpo interpretado.
 * @throws {Error} quando o corpo excede o teto ou não é JSON válido.
 */
function readJsonBody(req) {
  return new Promise((resolve, reject) => {
    let body = ''
    req.setEncoding('utf8')
    req.on('data', (chunk) => {
      body += chunk
      if (body.length > KEY_BODY_LIMIT) {
        reject(new Error('request body is too large'))
        req.destroy()
      }
    })
    req.on('end', () => {
      if (body.length === 0) return resolve({})
      try {
        const parsed = JSON.parse(body)
        resolve(parsed !== null && typeof parsed === 'object' ? parsed : {})
      } catch {
        reject(new Error('request body is not valid JSON'))
      }
    })
    req.on('error', reject)
  })
}

/**
 * Handler da chave: `GET` informa o estado sem revelar o valor; `POST` grava ou
 * remove. O valor nunca volta na resposta, nem para a própria página.
 *
 * Exportado para teste sem depender do servidor web do harness.
 *
 * @param {() => ({ describe: Function, set: Function, unset: Function } | undefined)} getCredentials - acessor do cofre.
 * @param {string} reference - nome da credencial (ex.: `TAVILY_API_KEY`).
 * @returns {(req: import('node:http').IncomingMessage, res: import('node:http').ServerResponse) => Promise<void>} handler HTTP.
 */
export function createKeyRoute(getCredentials, reference) {
  return async function keyRoute(req, res) {
    const credentials = getCredentials()
    if (credentials === undefined) {
      sendJson(res, 503, { error: 'the credential service is not available in this composition' })
      return
    }
    try {
      if (req.method === 'GET') {
        sendJson(res, 200, await describeKeyState(credentials, reference))
        return
      }
      if (req.method !== 'POST') {
        res.writeHead(405, { allow: 'GET, POST' })
        res.end()
        return
      }
      const body = await readJsonBody(req)
      if (body.action === 'remove') {
        await credentials.unset(reference)
      } else {
        const apiKey = typeof body.apiKey === 'string' ? body.apiKey.trim() : ''
        if (apiKey.length === 0) {
          sendJson(res, 400, { error: 'the key is empty' })
          return
        }
        if (/\s/.test(apiKey)) {
          sendJson(res, 400, { error: 'the key contains whitespace' })
          return
        }
        await credentials.set(reference, apiKey)
      }
      sendJson(res, 200, await describeKeyState(credentials, reference))
    } catch (cause) {
      const detail = cause instanceof Error ? cause.message : String(cause)
      sendJson(res, 500, { error: detail })
    }
  }
}

/**
 * Provedor de busca apoiado no endpoint `/search` da Tavily.
 *
 * A configuração é relida a cada operação, então mudar o profile não exige
 * re-registrar o provedor --- o que apareceria ao usuário como oscilação.
 */
export class TavilySearchProvider {
  /**
   * @param {() => ReturnType<typeof normalizeConfig>} resolveOptions - configuração da próxima operação.
   */
  constructor(resolveOptions) {
    this.resolveOptions = resolveOptions
    this.id = TAVILY_PROVIDER_ID
    /** @type {import('@deepseek-ai/cordis').Context | undefined} */
    this.ctx = undefined
  }

  /**
   * Checagem local barata, sem rede: o endpoint precisa ser válido e alguma
   * fonte de chave precisa existir. A chave em si só é lida na busca.
   *
   * @returns {boolean} se o provedor pode atender uma busca.
   */
  available() {
    const options = this.resolveOptions()
    if (!URL.canParse(options.baseURL)) return false
    if (options.apiKey !== undefined) return true
    if (existsSync(CLI_CONFIG_PATH)) return true
    const ambient = process.env[options.apiKeyEnv]
    if (typeof ambient === 'string' && ambient.trim().length > 0) return true
    // O cofre de credenciais é assíncrono e não pode ser consultado aqui; a
    // ausência da chave é reportada na busca com uma mensagem acionável.
    return this.ctx?.get('credentials') !== undefined
  }

  /**
   * Executa uma busca e devolve as fontes no formato do seam.
   *
   * @param {{ query: string, maxResults?: number }} request - consulta e teto de resultados.
   * @param {AbortSignal} [signal] - cancelamento/timeout do chamador.
   * @returns {Promise<{ content?: string, sources: Array<{ url: string, title?: string, snippet?: string, publishedAt?: string }>, truncated: boolean }>} resultado normalizado.
   * @throws {TavilyWebError} quando falta a chave, a rede falha ou a API recusa.
   */
  async search(request, signal) {
    const options = this.resolveOptions()
    const apiKey = await resolveApiKey(this.ctx, options)
    if (apiKey === undefined) {
      throw new TavilyWebError(
        `Tavily search is unavailable: no API key found. Set the ${options.apiKeyEnv} credential, ` +
          'export it in the harness environment, or run "tvly login --api-key" to store it.',
        'WEB_PROVIDER_UNAVAILABLE',
      )
    }
    if (signal?.aborted) throw signal.reason ?? new DOMException('The search was aborted', 'AbortError')

    const body = {
      query: request.query,
      search_depth: options.searchDepth,
      include_answer: options.includeAnswer,
    }
    if (typeof request.maxResults === 'number' && Number.isFinite(request.maxResults)) {
      body.max_results = Math.min(Math.max(Math.trunc(request.maxResults), 1), TAVILY_MAX_RESULTS)
    }

    let response
    try {
      response = await fetch(`${options.baseURL}/search`, {
        method: 'POST',
        headers: {
          'content-type': 'application/json',
          authorization: `Bearer ${apiKey}`,
        },
        body: JSON.stringify(body),
        ...(signal === undefined ? {} : { signal }),
      })
    } catch (cause) {
      // Cancelamento não é falha do provedor: preserva o AbortError.
      if (signal?.aborted) throw cause
      const detail = cause instanceof Error ? cause.message : String(cause)
      throw new TavilyWebError(`Tavily search request failed: ${detail}`, 'WEB_PROVIDER_ERROR', { cause })
    }

    if (!response.ok) {
      const suffix = await describeFailure(response)
      throw new TavilyWebError(`Tavily search failed with HTTP ${response.status}${suffix}`, 'WEB_PROVIDER_ERROR')
    }

    let payload
    try {
      payload = await response.json()
    } catch (cause) {
      throw new TavilyWebError('Tavily search returned a body that is not JSON', 'WEB_PROVIDER_ERROR', { cause })
    }
    return mapSearchResponse(payload)
  }
}

/**
 * Registra o provedor no seam. O `ctx` fica no provedor porque a busca resolve a
 * chave pelo cofre de credenciais e pelo ambiente de lançamento.
 *
 * @param {import('@deepseek-ai/cordis').Context} ctx - contexto do plugin.
 * @param {Record<string, unknown> | undefined} config - linha `config` do loader.
 * @returns {void}
 */
export function apply(ctx, config) {
  const options = normalizeConfig(config)
  const provider = new TavilySearchProvider(() => options)
  provider.ctx = ctx
  ctx.web.registerSearchProvider(provider)

  // Superfície de configuração: a página em Configurações fala com esta rota em
  // vez de o navegador tocar no cofre. Só existe onde há servidor web.
  ctx.inject(['webServer'], (webCtx) => {
    webCtx.effect(() =>
      webCtx.webServer.register({
        kind: 'exact',
        path: '/api/sig/tavily-key',
        handler: createKeyRoute(() => ctx.get('credentials'), options.apiKeyEnv),
      }),
    )
  })
}
