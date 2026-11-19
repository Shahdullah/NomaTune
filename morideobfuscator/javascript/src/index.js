import Innertube, {
  ClientType,
  Constants,
  JsAnalyzer,
  JsExtractor,
  JsMatchers,
  Platform,
  Player,
  Utils,
} from "youtubei.js/web.bundle";
import {
  CustomEvent,
  File,
  FormData,
  Headers,
  ReadableStream,
  Request,
  Response,
  base64ToBytes,
  bytesToBase64,
  fetch,
  installWebPlatform,
} from "./web-platform.js";
import { applyGvsPoToken } from "./stream-url.js";

const RUNTIME_VERSION = "18.0.0";
const SUPPORTED_AUDIO_MIME_TYPES = new Set([
  "audio/mp4",
  "audio/webm",
  "audio/ogg",
  "audio/mpeg",
  "audio/3gpp",
]);
const ANONYMOUS_CLIENT = "MWEB";
const AUTHENTICATED_CLIENT = "WEB_CREATOR";
const AUTHENTICATED_CLIENT_VERSION = "1.20260708.06.00";
const CATALOG_DURATION_TOLERANCE_SECONDS = 2;
const MAX_CATALOG_REPLACEMENT_CANDIDATES = 5;
const AUTH_COOKIE_NAMES = ["SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID"];
const FAILURE_KINDS = new Set([
  "LOGIN_REQUIRED",
  "UNAVAILABLE",
  "NO_FORMAT",
  "PO_TOKEN",
  "DECIPHER",
  "NETWORK",
  "HTTP",
  "TIMEOUT",
  "INVALID_RESPONSE",
  "INTERNAL",
]);

installWebPlatform();
Constants.CLIENTS.WEB_CREATOR.VERSION = AUTHENTICATED_CLIENT_VERSION;

class AndroidCache {
  get cache_dir() {
    return "android";
  }

  async get(key) {
    const encoded = await globalThis.__archiveTuneCacheRead(String(key));
    return encoded ? base64ToBytes(encoded).buffer : undefined;
  }

  async set(key, value) {
    const bytes = value instanceof ArrayBuffer ? new Uint8Array(value) : new Uint8Array(value.buffer);
    await globalThis.__archiveTuneCacheWrite(
      JSON.stringify({ key: String(key), value: bytesToBase64(bytes) }),
    );
  }

  async remove(key) {
    await globalThis.__archiveTuneCacheRemove(String(key));
  }
}

Platform.load({
  runtime: "unknown",
  server: true,
  Cache: AndroidCache,
  sha1Hash: globalThis.__archiveTuneSha1,
  uuidv4: globalThis.__archiveTuneUuid,
  eval(data, environment) {
    const names = Object.keys(environment || {});
    const values = names.map((name) => environment[name]);
    return new Function(...names, `"use strict";\n${data.output}`)(...values);
  },
  fetch,
  Request,
  Response,
  Headers,
  FormData,
  File,
  ReadableStream,
  CustomEvent,
});

let session;
let sessionIdentity;
let catalogSession;
let catalogSessionIdentity;
let stagedPlayerId;
let stagedPlayerSource;

function normalizedString(value) {
  const normalized = typeof value === "string" ? value.trim() : "";
  return normalized || undefined;
}

function cookieValue(cookie, name) {
  const prefix = `${name}=`;
  for (const entry of cookie.split(";")) {
    const normalized = entry.trim();
    if (!normalized.startsWith(prefix)) continue;
    return normalizedString(normalized.slice(prefix.length));
  }
  return undefined;
}

function youtubeiCookie(value) {
  const cookie = normalizedString(value);
  if (!cookie || cookieValue(cookie, AUTH_COOKIE_NAMES[0])) return cookie;
  const authenticationValue = AUTH_COOKIE_NAMES
    .slice(1)
    .map((name) => cookieValue(cookie, name))
    .find(Boolean);
  return authenticationValue ? `${cookie}; SAPISID=${authenticationValue}` : cookie;
}

function delegatedSessionId(value) {
  const dataSyncId = normalizedString(value);
  if (!dataSyncId) return undefined;
  const separatorIndex = dataSyncId.indexOf("||");
  if (separatorIndex <= 0 || separatorIndex + 2 >= dataSyncId.length) return undefined;
  return normalizedString(dataSyncId.slice(0, separatorIndex));
}

function createSessionIdentity(request) {
  return JSON.stringify({
    authFingerprint: request.authFingerprint,
    cookie: request.cookie,
    visitorData: request.visitorData,
    dataSyncId: request.dataSyncId,
    sessionPoToken: request.sessionPoToken,
    language: request.language,
    location: request.location,
    timezone: request.timezone,
  });
}

async function getSession(request) {
  const identity = createSessionIdentity(request);
  if (session && identity === sessionIdentity) return session;
  const cookie = youtubeiCookie(request.cookie);
  const authenticated = Boolean(cookie);
  session = await Innertube.create({
    cache: new AndroidCache(),
    client_type: authenticated ? ClientType.WEB_CREATOR : ClientType.MWEB,
    cookie,
    visitor_data: normalizedString(request.visitorData),
    on_behalf_of_user: authenticated ? delegatedSessionId(request.dataSyncId) : undefined,
    po_token: normalizedString(request.sessionPoToken),
    lang: normalizedString(request.language) || "en",
    location: normalizedString(request.location) || "US",
    timezone: normalizedString(request.timezone) || "UTC",
    generate_session_locally: true,
    retrieve_innertube_config: false,
    retrieve_player: false,
    enable_session_cache: false,
    fetch,
  });
  sessionIdentity = identity;
  stagedPlayerId = undefined;
  stagedPlayerSource = undefined;
  return session;
}

function createCatalogSessionIdentity(request) {
  return JSON.stringify({
    language: request.language,
    location: request.location,
    timezone: request.timezone,
  });
}

async function getCatalogSession(request) {
  const identity = createCatalogSessionIdentity(request);
  if (catalogSession && identity === catalogSessionIdentity) return catalogSession;
  catalogSession = await Innertube.create({
    cache: new AndroidCache(),
    client_type: ClientType.MUSIC,
    lang: normalizedString(request.language) || "en",
    location: normalizedString(request.location) || "US",
    timezone: normalizedString(request.timezone) || "UTC",
    generate_session_locally: true,
    retrieve_innertube_config: false,
    retrieve_player: false,
    enable_session_cache: false,
    fetch,
  });
  catalogSessionIdentity = identity;
  return catalogSession;
}

function bitrateOf(format) {
  return Number(format.average_bitrate || format.bitrate || 0);
}

function sampleRateOf(format) {
  return Number(format.audio_sample_rate || 0);
}

function isEligibleAudioFormat(format) {
  const mimeType = String(format.mime_type || "").split(";", 1)[0].toLowerCase();
  return Boolean(format.has_audio) &&
    !format.has_video &&
    !format.is_type_otf &&
    !format.fair_play_key_uri &&
    !format.drm_track_type &&
    !(format.drm_families && format.drm_families.length) &&
    SUPPORTED_AUDIO_MIME_TYPES.has(mimeType) &&
    Boolean(format.url || format.signature_cipher || format.cipher);
}

function compareFormats(first, second) {
  return bitrateOf(first) - bitrateOf(second) ||
    sampleRateOf(first) - sampleRateOf(second) ||
    String(first.itag).localeCompare(String(second.itag));
}

function preferredFormat(formats, request) {
  const effectiveQuality = request.quality === "AUTO"
    ? request.networkMetered ? "HIGH" : "HIGHEST"
    : request.quality;
  const target = effectiveQuality === "LOW" ? 70000 : effectiveQuality === "HIGH" ? 160000 : null;
  if (target === null) return formats[formats.length - 1];
  const notAboveTarget = formats.filter((format) => bitrateOf(format) <= target);
  return notAboveTarget.length ? notAboveTarget[notAboveTarget.length - 1] : formats[0];
}

function selectedFormat(formats, request) {
  const sorted = [...formats].sort(compareFormats);
  const pinned = request.pinnedItag
    ? sorted.find((format) => Number(format.itag) === Number(request.pinnedItag))
    : undefined;
  if (pinned) return pinned;
  const original = sorted.filter((format) => format.is_original);
  const primary = original.length ? original : sorted;
  return preferredFormat(primary, request);
}

function formatNeedsPlayer(format) {
  if (format.signature_cipher || format.cipher) return true;
  const url = normalizedString(format.url);
  if (!url) return false;
  return new URL(url).searchParams.has("n");
}

function playerError(message, kind, httpStatus) {
  const error = new Error(message);
  error.kind = kind;
  if (httpStatus) error.httpStatus = httpStatus;
  return error;
}

async function getCurrentPlayerId() {
  const response = await fetch(new URL("/iframe_api", Constants.URLS.YT_BASE));
  if (!response.ok) {
    throw playerError(
      `youtubei.js player ID request failed with HTTP ${response.status}`,
      "HTTP",
      response.status,
    );
  }
  const source = await response.text();
  const playerId = /player\\\/([A-Za-z0-9._-]+)\\\//.exec(source)?.[1];
  if (!playerId) {
    throw playerError("youtubei.js could not extract the current player ID", "DECIPHER");
  }
  return playerId;
}

async function downloadPlayerSource(playerId) {
  const url = new URL(
    `/s/player/${playerId}/player_es6.vflset/en_US/base.js`,
    Constants.URLS.YT_BASE,
  );
  const response = await fetch(url, {
    headers: {
      "user-agent": Utils.getRandomUserAgent("desktop"),
    },
  });
  if (!response.ok) {
    throw playerError(
      `youtubei.js player script request failed with HTTP ${response.status}`,
      "HTTP",
      response.status,
    );
  }
  const source = await response.text();
  if (!source) {
    throw playerError("youtubei.js returned an empty player script", "INVALID_RESPONSE");
  }
  return source;
}

function extractSignatureTimestamp(source) {
  const values = new Set(
    [...source.matchAll(/signatureTimestamp\s*:\s*(\d{5,})/g)].map((match) => match[1]),
  );
  if (values.size !== 1) {
    throw playerError("youtubei.js could not extract the player signature timestamp", "DECIPHER");
  }
  const timestamp = Number(values.values().next().value);
  if (!Number.isSafeInteger(timestamp) || timestamp <= 0) {
    throw playerError("youtubei.js returned an invalid player signature timestamp", "DECIPHER");
  }
  return timestamp;
}

function extractPlayerData(source) {
  const functionName = "nsigFunction";
  const analyzer = new JsAnalyzer(source, {
    extractions: [{ friendlyName: functionName, match: JsMatchers.nsigMatcher }],
  });
  const data = new JsExtractor(analyzer).buildScript({
    disallowSideEffectInitializers: true,
  });
  if (!data.exported.includes(functionName)) {
    throw playerError("youtubei.js could not extract the player decipher function", "DECIPHER");
  }
  return data;
}

async function ensurePlayerMetadata(youtube) {
  const existing = youtube.session.player;
  if (Number(existing?.signature_timestamp) > 0) return existing;

  const playerId = await getCurrentPlayerId();
  const cache = new AndroidCache();
  const cached = await Player.fromCache(cache, playerId);
  if (Number(cached?.signature_timestamp) > 0 && cached.data) {
    cached.po_token = youtube.session.po_token;
    youtube.session.player = cached;
    stagedPlayerId = undefined;
    stagedPlayerSource = undefined;
    return cached;
  }
  if (cached) await cache.remove(playerId);

  const source = await downloadPlayerSource(playerId);
  const player = new Player(playerId, extractSignatureTimestamp(source));
  player.po_token = youtube.session.po_token;
  youtube.session.player = player;
  stagedPlayerId = playerId;
  stagedPlayerSource = source;
  return player;
}

async function getPlayer(youtube) {
  const metadata = await ensurePlayerMetadata(youtube);
  if (metadata.data) return metadata;

  const source = stagedPlayerId === metadata.player_id && stagedPlayerSource
    ? stagedPlayerSource
    : await downloadPlayerSource(metadata.player_id);
  try {
    const player = await Player.fromSource(metadata.player_id, {
      cache: new AndroidCache(),
      signature_timestamp: metadata.signature_timestamp,
      data: extractPlayerData(source),
    });
    player.po_token = youtube.session.po_token;
    youtube.session.player = player;
    return player;
  } finally {
    stagedPlayerId = undefined;
    stagedPlayerSource = undefined;
  }
}

function extractExpiry(url, streamingDataExpiry) {
  const parsedExpiry = streamingDataExpiry instanceof Date
    ? streamingDataExpiry.getTime()
    : Number(streamingDataExpiry);
  if (Number.isFinite(parsedExpiry) && parsedExpiry > Date.now()) return parsedExpiry;
  const value = Number(new URL(url).searchParams.get("expire"));
  return Number.isFinite(value) && value > 0 ? value * 1000 : Date.now() + 300000;
}

function thumbnailUrl(basicInfo) {
  const thumbnails = basicInfo?.thumbnail;
  if (!Array.isArray(thumbnails) || thumbnails.length === 0) return undefined;
  return normalizedString(thumbnails[thumbnails.length - 1]?.url);
}

function playbackTrackingUrl(info) {
  return normalizedString(info.page?.[0]?.playback_tracking?.videostats_playback_url);
}

function playbackUserAgent(youtube, client) {
  const clientUserAgent = Constants.CLIENTS[client]?.USER_AGENT ||
    Object.values(Constants.CLIENTS).find((candidate) => candidate.NAME === client)?.USER_AGENT;
  return normalizedString(clientUserAgent) ||
    normalizedString(youtube.session.context?.client?.userAgent);
}

function failureMessage(error, fallback = "youtubei.js resolution failed") {
  if (typeof error === "string") return normalizedString(error) || fallback;
  if (!error || typeof error !== "object") return fallback;
  return normalizedString(error.message) ||
    normalizedString(error.info?.reason) ||
    normalizedString(error.info?.message) ||
    normalizedString(error.info?.error_type) ||
    normalizedString(error.info?.status) ||
    fallback;
}

function structuredFailureKind(error) {
  if (!error || typeof error !== "object") return undefined;
  const candidates = [error.kind, error.info?.error_type, error.info?.status, error.status];
  for (const candidate of candidates) {
    const value = normalizedString(candidate)?.toUpperCase().replace(/[ -]+/g, "_");
    if (!value) continue;
    if (FAILURE_KINDS.has(value)) return value;
    if (
      value === "AGE_CHECK_REQUIRED" ||
      value === "AGE_RESTRICTED" ||
      value === "AGE_VERIFICATION_REQUIRED" ||
      value === "CONTENT_CHECK_REQUIRED" ||
      value === "AUTH_REQUIRED"
    ) return "LOGIN_REQUIRED";
    if (
      value === "UNPLAYABLE" ||
      value === "VIDEO_UNAVAILABLE" ||
      value === "CONTENT_UNAVAILABLE"
    ) return "UNAVAILABLE";
    if (value === "NO_STREAMING_DATA" || value === "FORMAT_NOT_FOUND") return "NO_FORMAT";
    if (value === "SIGNATURE_DECIPHER_FAILED" || value === "NSIG_DECIPHER_FAILED") return "DECIPHER";
    if (value === "FETCH_ERROR" || value === "FETCH_FAILED") return "NETWORK";
  }
  return undefined;
}

function parsedHttpErrorInfo(error) {
  if (typeof error?.info !== "string") return undefined;
  try {
    return JSON.parse(error.info);
  } catch {
    return undefined;
  }
}

function httpStatusOf(error) {
  const candidates = [error?.httpStatus, error?.status, error?.response?.status];
  const info = parsedHttpErrorInfo(error);
  candidates.push(info?.error?.code, info?.code);
  for (const candidate of candidates) {
    const status = Number(candidate);
    if (Number.isInteger(status) && status >= 100 && status <= 599) return status;
  }
  const match = /status code (\d{3})/i.exec(String(error?.message || ""));
  if (!match) return undefined;
  const status = Number(match[1]);
  return status >= 100 && status <= 599 ? status : undefined;
}

function failureKind(error) {
  const structured = structuredFailureKind(error);
  if (structured) return structured;
  if (httpStatusOf(error)) return "HTTP";
  const message = failureMessage(error);
  const normalized = message.toLowerCase();
  if (
    normalized.includes("sign in") ||
    normalized.includes("login required") ||
    normalized.includes("confirm your age") ||
    normalized.includes("age-restricted")
  ) return "LOGIN_REQUIRED";
  if (normalized.includes("unavailable") || normalized.includes("private video")) return "UNAVAILABLE";
  if (normalized.includes("format")) return "NO_FORMAT";
  if (normalized.includes("decipher") || normalized.includes("signature") || normalized.includes("nsig")) return "DECIPHER";
  if (normalized.includes("timed out") || normalized.includes("timeout")) return "TIMEOUT";
  if (normalized.includes("http ")) return "HTTP";
  if (error instanceof TypeError && normalized.includes("network")) return "NETWORK";
  return "INTERNAL";
}

function normalizedFailure(error, fallbackMessage) {
  const failure = {
    kind: failureKind(error),
    message: failureMessage(error, fallbackMessage),
  };
  const httpStatus = httpStatusOf(error);
  return httpStatus ? { ...failure, httpStatus } : failure;
}

function playabilityError(status, reason) {
  const error = new Error(reason || `YouTube playability status: ${status}`);
  const reasonKind = failureKind(error);
  error.kind = reasonKind === "LOGIN_REQUIRED"
    ? reasonKind
    : structuredFailureKind({ status }) || (reasonKind === "INTERNAL" ? "UNAVAILABLE" : reasonKind);
  return error;
}

function catalogMetadataKey(value) {
  return normalizedString(value)
    ?.normalize("NFKD")
    .toLowerCase()
    .replace(/[\p{M}\p{P}\p{S}\s]+/gu, "");
}

function catalogIdentity(info) {
  const basicInfo = info?.basic_info;
  const isAutoGeneratedCatalogEntry = /Auto-generated by YouTube\.\s*$/i.test(
    normalizedString(basicInfo?.short_description) || "",
  );
  if (
    !basicInfo ||
    !isAutoGeneratedCatalogEntry ||
    basicInfo.is_private === true ||
    basicInfo.is_live === true ||
    basicInfo.is_live_content === true
  ) return undefined;

  const title = normalizedString(basicInfo.title);
  const author = normalizedString(basicInfo.author);
  const channelId = normalizedString(basicInfo.channel_id);
  const durationSeconds = Number(basicInfo.duration || 0);
  const titleKey = catalogMetadataKey(title);
  const authorKey = catalogMetadataKey(author);
  const albumNames = [...new Set(
    (Array.isArray(basicInfo.keywords) ? basicInfo.keywords : [])
      .map(normalizedString)
      .filter(Boolean)
      .filter((keyword) => {
        const key = catalogMetadataKey(keyword);
        return key && key !== titleKey && key !== authorKey;
      }),
  )];
  if (!title || !channelId || !titleKey || !Number.isFinite(durationSeconds) || durationSeconds <= 0) {
    return undefined;
  }
  if (albumNames.length === 0) return undefined;
  return {
    title,
    titleKey,
    author,
    channelId,
    durationSeconds,
    albumKeys: new Set(albumNames.map(catalogMetadataKey)),
    query: [title, author, ...albumNames].filter(Boolean).join(" "),
  };
}

function catalogSearchCandidates(search, identity, requestedMediaId) {
  return (search?.contents || [])
    .flatMap((section) => section?.contents || [])
    .filter((candidate) =>
      candidate?.item_type === "song" &&
      normalizedString(candidate.id) !== requestedMediaId &&
      catalogMetadataKey(candidate.title) === identity.titleKey &&
      identity.albumKeys.has(catalogMetadataKey(candidate.album?.name)) &&
      (candidate.artists || []).some(
        (artist) => normalizedString(artist?.channel_id) === identity.channelId,
      ),
    )
    .slice(0, MAX_CATALOG_REPLACEMENT_CANDIDATES);
}

function isSameCatalogRecording(info, identity) {
  const basicInfo = info?.basic_info;
  const durationSeconds = Number(basicInfo?.duration || 0);
  return normalizedString(info?.playability_status?.status) === "OK" &&
    catalogMetadataKey(basicInfo?.title) === identity.titleKey &&
    Number.isFinite(durationSeconds) &&
    Math.abs(durationSeconds - identity.durationSeconds) <= CATALOG_DURATION_TOLERANCE_SECONDS;
}

async function resolveCatalogReplacement(youtube, request, sourceInfo) {
  if (!normalizedString(request.cookie)) return undefined;
  const identity = catalogIdentity(sourceInfo);
  if (!identity) return undefined;
  const catalog = await getCatalogSession(request);
  const search = await catalog.music.search(identity.query, { type: "song" });
  const candidates = catalogSearchCandidates(search, identity, request.mediaId);
  if (candidates.length !== 1) return undefined;

  const candidate = candidates[0];
  const videoPoToken = normalizedString(
    await globalThis.__archiveTuneVideoPoToken(candidate.id),
  );
  if (!videoPoToken) {
    const error = new Error("youtubei.js could not mint a content-bound playback token");
    error.kind = "PO_TOKEN";
    throw error;
  }
  const replacementRequest = {
    ...request,
    mediaId: candidate.id,
    videoPoToken,
  };
  const info = await youtube.getBasicInfo(candidate.id, {
    client: AUTHENTICATED_CLIENT,
    po_token: videoPoToken,
  });
  if (!isSameCatalogRecording(info, identity)) return undefined;
  return resolveWithClient(
    youtube,
    replacementRequest,
    AUTHENTICATED_CLIENT,
    info,
  );
}

async function resolveWithClient(youtube, request, client, preparedInfo) {
  const supportsGvsPoToken = client === AUTHENTICATED_CLIENT || client === ANONYMOUS_CLIENT;
  if (supportsGvsPoToken && !preparedInfo) await ensurePlayerMetadata(youtube);
  const info = preparedInfo ||
    await youtube.getBasicInfo(request.mediaId, {
      client,
      po_token: supportsGvsPoToken ? normalizedString(request.videoPoToken) : undefined,
    });
  const status = normalizedString(info.playability_status?.status);
  const reason = normalizedString(info.playability_status?.reason);
  if (status && status !== "OK") {
    const error = playabilityError(status, reason);
    error.videoInfo = info;
    throw error;
  }
  const formats = [
    ...(info.streaming_data?.adaptive_formats || []),
    ...(info.streaming_data?.formats || []),
  ].filter(isEligibleAudioFormat);
  if (formats.length === 0) {
    const error = new Error(reason || "No direct audio format");
    error.kind = "NO_FORMAT";
    throw error;
  }

  const format = selectedFormat(formats, request);
  let player;
  try {
    player = formatNeedsPlayer(format) ? await getPlayer(youtube) : undefined;
  } catch (cause) {
    if (failureKind(cause) !== "INTERNAL") throw cause;
    const error = new Error(failureMessage(cause, "youtubei.js could not prepare URL deciphering"));
    error.kind = "DECIPHER";
    throw error;
  }
  let url;
  try {
    url = await format.decipher(player);
  } catch (cause) {
    const error = new Error(failureMessage(cause, "youtubei.js could not decipher the audio URL"));
    error.kind = "DECIPHER";
    throw error;
  }
  const parsedUrl = applyGvsPoToken(new URL(url), {
    supportsGvsPoToken,
    videoPoToken: request.videoPoToken,
    sessionPoToken: request.sessionPoToken,
  });
  if (parsedUrl.protocol !== "https:" && parsedUrl.protocol !== "http:") {
    const error = new Error("youtubei.js returned an unsupported audio URL");
    error.kind = "DECIPHER";
    throw error;
  }
  const mime = String(format.mime_type || "audio/webm");
  const mimeType = mime.split(";", 1)[0];
  const codecs = /codecs="([^"]+)"/.exec(mime)?.[1] || "";
  const basicInfo = info.basic_info || {};
  const headers = {
    Accept: "*/*",
    Origin: "https://www.youtube.com",
    Referer: "https://www.youtube.com/",
  };
  const userAgent = playbackUserAgent(youtube, client);
  if (userAgent) headers["User-Agent"] = userAgent;
  const audioConfig = info.player_config?.audio_config;
  return {
    url: parsedUrl.toString(),
    headers,
    formatId: Number(format.itag),
    mimeType,
    codecs,
    bitrate: bitrateOf(format),
    sampleRate: sampleRateOf(format) || null,
    contentLength: Number(format.content_length || 0),
    expiresAtMs: extractExpiry(parsedUrl, info.streaming_data?.expires),
    runtimeVersion: RUNTIME_VERSION,
    title: normalizedString(basicInfo.title),
    durationSeconds: Number(basicInfo.duration || 0) || null,
    thumbnailUrl: thumbnailUrl(basicInfo),
    loudnessDb: Number.isFinite(format.loudness_db)
      ? format.loudness_db
      : Number.isFinite(audioConfig?.loudness_db) ? audioConfig.loudness_db : null,
    perceptualLoudnessDb: Number.isFinite(audioConfig?.perceptual_loudness_db)
      ? audioConfig.perceptual_loudness_db
      : null,
    playbackTrackingUrl: playbackTrackingUrl(info),
  };
}

function parseRequest(requestJson) {
  const request = JSON.parse(requestJson);
  if (!/^[A-Za-z0-9_-]{6,32}$/.test(String(request.mediaId || ""))) {
    const error = new Error("Invalid YouTube media ID");
    error.kind = "INVALID_RESPONSE";
    throw error;
  }
  return request;
}

async function prepare(requestJson) {
  try {
    await getSession(parseRequest(requestJson));
    return JSON.stringify({ ok: true });
  } catch (error) {
    return JSON.stringify({ ok: false, error: normalizedFailure(error) });
  }
}

async function resolvePrepared(requestJson) {
  try {
    const request = parseRequest(requestJson);
    const identity = createSessionIdentity(request);
    if (!session || identity !== sessionIdentity) {
      const error = new Error("youtubei.js session was not prepared");
      error.kind = "INTERNAL";
      throw error;
    }
    const authenticated = Boolean(normalizedString(request.cookie));
    const client = authenticated ? AUTHENTICATED_CLIENT : ANONYMOUS_CLIENT;
    let value;
    try {
      value = await resolveWithClient(session, request, client);
    } catch (error) {
      if (failureKind(error) !== "UNAVAILABLE" || !error.videoInfo) throw error;
      value = await resolveCatalogReplacement(session, request, error.videoInfo);
      if (!value) throw error;
    }
    return JSON.stringify({ ok: true, value });
  } catch (error) {
    return JSON.stringify({
      ok: false,
      error: normalizedFailure(error),
    });
  } finally {
    stagedPlayerId = undefined;
    stagedPlayerSource = undefined;
  }
}

globalThis.ArchiveTuneYoutubei = Object.freeze({
  version: RUNTIME_VERSION,
  prepare,
  resolvePrepared,
  reset() {
    session = undefined;
    sessionIdentity = undefined;
    catalogSession = undefined;
    catalogSessionIdentity = undefined;
    stagedPlayerId = undefined;
    stagedPlayerSource = undefined;
  },
});
