import { URL, URLSearchParams } from "whatwg-url";

const BASE64_ALPHABET =
  "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
const PLAYER_SOURCE_ERROR_PREFIX = "ARCHIVETUNE_PLAYER_SOURCE_ERROR|";

function encodeUtf8(value) {
  const encoded = unescape(encodeURIComponent(String(value)));
  const bytes = new Uint8Array(encoded.length);
  for (let index = 0; index < encoded.length; index += 1) {
    bytes[index] = encoded.charCodeAt(index);
  }
  return bytes;
}

function decodeUtf8(bytes) {
  let encoded = "";
  for (let index = 0; index < bytes.length; index += 1) {
    encoded += String.fromCharCode(bytes[index]);
  }
  return decodeURIComponent(escape(encoded));
}

function bytesToBase64(bytes) {
  let result = "";
  for (let index = 0; index < bytes.length; index += 3) {
    const first = bytes[index];
    const second = bytes[index + 1];
    const third = bytes[index + 2];
    const combined = (first << 16) | ((second || 0) << 8) | (third || 0);
    result += BASE64_ALPHABET[(combined >>> 18) & 63];
    result += BASE64_ALPHABET[(combined >>> 12) & 63];
    result += index + 1 < bytes.length ? BASE64_ALPHABET[(combined >>> 6) & 63] : "=";
    result += index + 2 < bytes.length ? BASE64_ALPHABET[combined & 63] : "=";
  }
  return result;
}

function base64ToBytes(value) {
  const normalized = String(value).replace(/-/g, "+").replace(/_/g, "/").replace(/\s/g, "");
  if (normalized.length % 4 === 1) {
    throw new TypeError("Invalid base64 input");
  }
  const padded = normalized.padEnd(normalized.length + ((4 - (normalized.length % 4)) % 4), "=");
  const padding = padded.endsWith("==") ? 2 : padded.endsWith("=") ? 1 : 0;
  const bytes = new Uint8Array((padded.length / 4) * 3 - padding);
  let outputIndex = 0;
  for (let index = 0; index < padded.length; index += 4) {
    const first = BASE64_ALPHABET.indexOf(padded[index]);
    const second = BASE64_ALPHABET.indexOf(padded[index + 1]);
    const third = padded[index + 2] === "=" ? 0 : BASE64_ALPHABET.indexOf(padded[index + 2]);
    const fourth = padded[index + 3] === "=" ? 0 : BASE64_ALPHABET.indexOf(padded[index + 3]);
    if (first < 0 || second < 0 || third < 0 || fourth < 0) {
      throw new TypeError("Invalid base64 input");
    }
    const combined = (first << 18) | (second << 12) | (third << 6) | fourth;
    bytes[outputIndex] = (combined >>> 16) & 255;
    outputIndex += 1;
    if (padded[index + 2] !== "=") {
      bytes[outputIndex] = (combined >>> 8) & 255;
      outputIndex += 1;
    }
    if (padded[index + 3] !== "=") {
      bytes[outputIndex] = combined & 255;
      outputIndex += 1;
    }
  }
  return bytes;
}

function bodyToBytes(body) {
  if (body === undefined || body === null) return new Uint8Array();
  if (typeof body === "string") return encodeUtf8(body);
  if (body instanceof URLSearchParams) return encodeUtf8(body.toString());
  if (body instanceof ArrayBuffer) return new Uint8Array(body.slice(0));
  if (ArrayBuffer.isView(body)) {
    return new Uint8Array(body.buffer.slice(body.byteOffset, body.byteOffset + body.byteLength));
  }
  if (body instanceof Blob) return body.bytes();
  throw new TypeError("Unsupported request body");
}

class Headers {
  #values = new Map();

  constructor(init) {
    if (!init) return;
    if (init instanceof Headers) {
      init.forEach((value, name) => this.append(name, value));
      return;
    }
    if (Array.isArray(init) || typeof init[Symbol.iterator] === "function") {
      for (const entry of init) {
        if (!Array.isArray(entry) || entry.length !== 2) {
          throw new TypeError("Invalid header entry");
        }
        this.append(entry[0], entry[1]);
      }
      return;
    }
    Object.entries(init).forEach(([name, value]) => this.append(name, value));
  }

  append(name, value) {
    const normalizedName = Headers.#normalizeName(name);
    const normalizedValue = Headers.#normalizeValue(value);
    const current = this.#values.get(normalizedName);
    this.#values.set(normalizedName, current ? `${current}, ${normalizedValue}` : normalizedValue);
  }

  delete(name) {
    this.#values.delete(Headers.#normalizeName(name));
  }

  get(name) {
    return this.#values.get(Headers.#normalizeName(name)) ?? null;
  }

  has(name) {
    return this.#values.has(Headers.#normalizeName(name));
  }

  set(name, value) {
    this.#values.set(Headers.#normalizeName(name), Headers.#normalizeValue(value));
  }

  entries() {
    return this.#values.entries();
  }

  keys() {
    return this.#values.keys();
  }

  values() {
    return this.#values.values();
  }

  forEach(callback, thisArg) {
    this.#values.forEach((value, name) => callback.call(thisArg, value, name, this));
  }

  [Symbol.iterator]() {
    return this.entries();
  }

  static #normalizeName(name) {
    const normalized = String(name).trim().toLowerCase();
    if (!normalized || !/^[!#$%&'*+\-.^_`|~0-9a-z]+$/.test(normalized)) {
      throw new TypeError("Invalid header name");
    }
    return normalized;
  }

  static #normalizeValue(value) {
    const normalized = String(value).trim();
    if (/\r|\n/.test(normalized)) throw new TypeError("Invalid header value");
    return normalized;
  }
}

class Blob {
  #bytes;

  constructor(parts = [], options = {}) {
    const chunks = parts.map((part) => bodyToBytes(part));
    const size = chunks.reduce((total, chunk) => total + chunk.length, 0);
    this.#bytes = new Uint8Array(size);
    let offset = 0;
    chunks.forEach((chunk) => {
      this.#bytes.set(chunk, offset);
      offset += chunk.length;
    });
    this.type = String(options.type || "").toLowerCase();
  }

  get size() {
    return this.#bytes.length;
  }

  arrayBuffer() {
    return Promise.resolve(this.#bytes.buffer.slice(0));
  }

  bytes() {
    return this.#bytes.slice();
  }

  text() {
    return Promise.resolve(decodeUtf8(this.#bytes));
  }
}

class File extends Blob {
  constructor(parts, name, options = {}) {
    super(parts, options);
    this.name = String(name);
    this.lastModified = Number(options.lastModified || Date.now());
  }
}

class BodyContainer {
  #bytes;

  constructor(body) {
    this.#bytes = bodyToBytes(body);
    this.bodyUsed = false;
  }

  #consume() {
    if (this.bodyUsed) throw new TypeError("Body has already been consumed");
    this.bodyUsed = true;
    return this.#bytes.slice();
  }

  arrayBuffer() {
    return Promise.resolve(this.#consume().buffer);
  }

  blob() {
    return Promise.resolve(new Blob([this.#consume()]));
  }

  bytes() {
    return Promise.resolve(this.#consume());
  }

  json() {
    return this.text().then(JSON.parse);
  }

  text() {
    return Promise.resolve(decodeUtf8(this.#consume()));
  }

  cloneBytes() {
    return this.#bytes.slice();
  }
}

class Request extends BodyContainer {
  constructor(input, init = {}) {
    const source = input instanceof Request ? input : null;
    const url = source ? source.url : new URL(String(input)).toString();
    const body = init.body !== undefined ? init.body : source?.cloneBytes();
    super(body);
    this.url = url;
    this.method = String(init.method || source?.method || "GET").toUpperCase();
    this.headers = new Headers(init.headers || source?.headers);
    this.credentials = init.credentials || source?.credentials || "same-origin";
    this.redirect = init.redirect || source?.redirect || "follow";
    this.signal = init.signal || source?.signal || null;
  }

  clone() {
    return new Request(this);
  }
}

class Response extends BodyContainer {
  constructor(body, init = {}) {
    super(body);
    this.status = Number(init.status === undefined ? 200 : init.status);
    this.statusText = String(init.statusText || "");
    this.headers = new Headers(init.headers);
    this.url = String(init.url || "");
    this.redirected = Boolean(init.redirected);
    this.type = "default";
  }

  get ok() {
    return this.status >= 200 && this.status < 300;
  }

  clone() {
    return new Response(this.cloneBytes(), {
      status: this.status,
      statusText: this.statusText,
      headers: this.headers,
      url: this.url,
      redirected: this.redirected,
    });
  }

  static error() {
    return new Response(null, { status: 0 });
  }

  static redirect(url, status = 302) {
    return new Response(null, { status, headers: { location: new URL(url).toString() } });
  }
}

class TextResponse extends Response {
  #text;

  constructor(text, init = {}) {
    super(null, init);
    this.#text = String(text);
  }

  #consumeText() {
    if (this.bodyUsed) throw new TypeError("Body has already been consumed");
    this.bodyUsed = true;
    return this.#text;
  }

  arrayBuffer() {
    return Promise.resolve(encodeUtf8(this.#consumeText()).buffer);
  }

  blob() {
    return Promise.resolve(new Blob([this.#consumeText()]));
  }

  bytes() {
    return Promise.resolve(encodeUtf8(this.#consumeText()));
  }

  json() {
    return Promise.resolve(JSON.parse(this.#consumeText()));
  }

  text() {
    return Promise.resolve(this.#consumeText());
  }

  clone() {
    if (this.bodyUsed) throw new TypeError("Body has already been consumed");
    return new TextResponse(this.#text, {
      status: this.status,
      statusText: this.statusText,
      headers: this.headers,
      url: this.url,
      redirected: this.redirected,
    });
  }
}

class FormData {
  #entries = [];

  append(name, value, filename) {
    this.#entries.push([String(name), filename ? new File([value], filename) : value]);
  }

  set(name, value, filename) {
    this.delete(name);
    this.append(name, value, filename);
  }

  get(name) {
    return this.#entries.find(([entryName]) => entryName === String(name))?.[1] ?? null;
  }

  getAll(name) {
    return this.#entries.filter(([entryName]) => entryName === String(name)).map((entry) => entry[1]);
  }

  has(name) {
    return this.#entries.some(([entryName]) => entryName === String(name));
  }

  delete(name) {
    this.#entries = this.#entries.filter(([entryName]) => entryName !== String(name));
  }

  entries() {
    return this.#entries[Symbol.iterator]();
  }

  [Symbol.iterator]() {
    return this.entries();
  }
}

class EventTarget {
  #listeners = new Map();

  addEventListener(type, listener) {
    const listeners = this.#listeners.get(type) || new Set();
    listeners.add(listener);
    this.#listeners.set(type, listeners);
  }

  removeEventListener(type, listener) {
    this.#listeners.get(type)?.delete(listener);
  }

  dispatchEvent(event) {
    event.target = this;
    this.#listeners.get(event.type)?.forEach((listener) => listener.call(this, event));
    return !event.defaultPrevented;
  }
}

class CustomEvent {
  constructor(type, init = {}) {
    this.type = String(type);
    this.detail = init.detail;
    this.defaultPrevented = false;
    this.target = null;
  }

  preventDefault() {
    this.defaultPrevented = true;
  }
}

class ReadableStream {
  constructor() {
    throw new TypeError("Streaming response bodies are not available in the extraction runtime");
  }
}

function isPlayerScriptRequest(request) {
  if (request.method !== "GET") return false;
  const url = new URL(request.url);
  if (url.protocol !== "https:") return false;
  const host = url.hostname.toLowerCase();
  if (host !== "www.youtube.com" && host !== "youtube.com") return false;
  return /^\/s\/player\/[A-Za-z0-9._-]+\/player_es6\.vflset\/[A-Za-z0-9._-]+\/base\.js$/.test(
    url.pathname,
  );
}

function playerSourceError(payload) {
  const details = payload.startsWith(PLAYER_SOURCE_ERROR_PREFIX)
    ? payload.slice(PLAYER_SOURCE_ERROR_PREFIX.length)
    : "";
  const separator = details.indexOf("|");
  const kind = separator > 0 ? details.slice(0, separator) : "NETWORK";
  const message = separator > 0 ? details.slice(separator + 1) : "Player script request failed";
  const error = new TypeError(message || "Player script request failed");
  error.kind = kind || "NETWORK";
  return error;
}

async function fetch(input, init = {}) {
  const request = new Request(input, init);
  if (isPlayerScriptRequest(request)) {
    const source = await globalThis.__archiveTunePlayerSource(
      JSON.stringify({
        url: request.url,
        method: request.method,
        headers: Object.fromEntries(request.headers),
      }),
    );
    if (!source || source.startsWith(PLAYER_SOURCE_ERROR_PREFIX)) {
      throw playerSourceError(source || "");
    }
    return new TextResponse(source, {
      status: 200,
      statusText: "OK",
      url: request.url,
    });
  }
  const responseJson = await globalThis.__archiveTuneHttp(
    JSON.stringify({
      url: request.url,
      method: request.method,
      headers: Object.fromEntries(request.headers),
      bodyBase64: bytesToBase64(request.cloneBytes()),
    }),
  );
  const response = JSON.parse(responseJson);
  if (!response || response.ok !== true) {
    const error = new TypeError(response?.message || "Network request failed");
    error.kind = response?.kind || "NETWORK";
    throw error;
  }
  return new Response(base64ToBytes(response.bodyBase64 || ""), {
    status: response.status,
    statusText: response.statusText,
    headers: response.headers,
    url: response.url,
    redirected: response.redirected,
  });
}

function installWebPlatform() {
  Object.assign(globalThis, {
    URL,
    URLSearchParams,
    Headers,
    Request,
    Response,
    FormData,
    File,
    Blob,
    ReadableStream,
    EventTarget,
    CustomEvent,
    TextEncoder: class {
      encode(value) {
        return encodeUtf8(value);
      }
    },
    TextDecoder: class {
      decode(value = new Uint8Array()) {
        return decodeUtf8(value instanceof Uint8Array ? value : new Uint8Array(value));
      }
    },
    atob(value) {
      return String.fromCharCode(...base64ToBytes(value));
    },
    btoa(value) {
      return bytesToBase64(Uint8Array.from(String(value), (character) => character.charCodeAt(0)));
    },
    fetch,
  });
  globalThis.self = globalThis;
  globalThis.window = globalThis;
  globalThis.navigator = globalThis.navigator || { userAgent: "ArchiveTune/Android" };
  globalThis.performance = globalThis.performance || { now: () => Date.now() };
  globalThis.crypto = globalThis.crypto || {
    getRandomValues(array) {
      const random = base64ToBytes(globalThis.__archiveTuneRandom(array.byteLength));
      array.set(random.subarray(0, array.length));
      return array;
    },
    randomUUID: globalThis.__archiveTuneUuid,
  };
}

export {
  Blob,
  CustomEvent,
  File,
  FormData,
  Headers,
  ReadableStream,
  Request,
  Response,
  URL,
  URLSearchParams,
  base64ToBytes,
  bytesToBase64,
  fetch,
  installWebPlatform,
};
