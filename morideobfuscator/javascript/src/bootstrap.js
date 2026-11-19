(() => {
  function encodeUtf8(value) {
    const encoded = unescape(encodeURIComponent(String(value)));
    const bytes = new Uint8Array(encoded.length);
    for (let index = 0; index < encoded.length; index += 1) {
      bytes[index] = encoded.charCodeAt(index);
    }
    return bytes;
  }

  function decodeUtf8(value) {
    const bytes = value instanceof Uint8Array ? value : new Uint8Array(value || 0);
    let encoded = "";
    for (let index = 0; index < bytes.length; index += 1) {
      encoded += String.fromCharCode(bytes[index]);
    }
    return decodeURIComponent(escape(encoded));
  }

  if (typeof globalThis.TextEncoder !== "function") {
    globalThis.TextEncoder = class {
      encode(value = "") {
        return encodeUtf8(value);
      }
    };
  }

  if (typeof globalThis.TextDecoder !== "function") {
    globalThis.TextDecoder = class {
      decode(value = new Uint8Array()) {
        return decodeUtf8(value);
      }
    };
  }

  if (typeof globalThis.EventTarget !== "function") {
    globalThis.EventTarget = class {
      constructor() {
        this.listeners = new Map();
      }

      addEventListener(type, listener) {
        const listeners = this.listeners.get(type) || new Set();
        listeners.add(listener);
        this.listeners.set(type, listeners);
      }

      removeEventListener(type, listener) {
        this.listeners.get(type)?.delete(listener);
      }

      dispatchEvent(event) {
        event.target = this;
        this.listeners.get(event.type)?.forEach((listener) => listener.call(this, event));
        return !event.defaultPrevented;
      }
    };
  }

  if (!globalThis.console) {
    const noOp = () => undefined;
    globalThis.console = Object.freeze({
      debug: noOp,
      error: noOp,
      info: noOp,
      log: noOp,
      warn: noOp,
    });
  }
})();
