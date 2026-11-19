(function () {
    "use strict";

    const arrayPrototype = Array.prototype;
    const nativeJoin = arrayPrototype.join;
    const nativeToLocaleString = arrayPrototype.toLocaleString;
    const apply = Reflect.apply;
    const activeArrays = new WeakSet();
    const maxConversionDepth = 128;
    let conversionDepth = 0;

    function convertArray(receiver, method, args) {
        if (receiver === null || receiver === undefined) {
            return apply(method, receiver, args);
        }

        const object = Object(receiver);
        if (activeArrays.has(object)) {
            return "";
        }
        if (conversionDepth >= maxConversionDepth) {
            throw new RangeError("Array string conversion is nested too deeply");
        }

        activeArrays.add(object);
        conversionDepth += 1;
        try {
            return apply(method, object, args);
        } finally {
            conversionDepth -= 1;
            activeArrays.delete(object);
        }
    }

    const methods = {
        join(separator) {
            return convertArray(this, nativeJoin, [separator]);
        },
        toLocaleString() {
            return convertArray(this, nativeToLocaleString, arguments);
        },
    };

    for (const name of ["join", "toLocaleString"]) {
        const descriptor = Object.getOwnPropertyDescriptor(arrayPrototype, name);
        descriptor.value = methods[name];
        Object.defineProperty(arrayPrototype, name, descriptor);
    }
})();

(function (global) {
    if (typeof global.structuredClone === "function") {
        return;
    }

    global.structuredClone = function (value, options) {
        if (options && options.transfer && options.transfer.length > 0) {
            throw new TypeError("Transferable objects are not supported");
        }

        const seen = new Map();

        function clone(input) {
            const inputType = typeof input;
            if (inputType === "function" || inputType === "symbol") {
                throw new TypeError("Value cannot be cloned");
            }
            if (input === null || inputType !== "object") {
                return input;
            }
            if (seen.has(input)) {
                return seen.get(input);
            }

            if (input instanceof Date) {
                const output = new Date(input.getTime());
                seen.set(input, output);
                return output;
            }
            if (input instanceof RegExp) {
                const output = new RegExp(input.source, input.flags);
                seen.set(input, output);
                return output;
            }
            if (typeof ArrayBuffer !== "undefined" && input instanceof ArrayBuffer) {
                const output = input.slice(0);
                seen.set(input, output);
                return output;
            }
            if (typeof ArrayBuffer !== "undefined" && ArrayBuffer.isView(input)) {
                const output = typeof DataView !== "undefined" && input instanceof DataView
                    ? new DataView(clone(input.buffer), input.byteOffset, input.byteLength)
                    : new input.constructor(input);
                seen.set(input, output);
                return output;
            }
            if (input instanceof Map) {
                const output = new Map();
                seen.set(input, output);
                input.forEach(function (entryValue, entryKey) {
                    output.set(clone(entryKey), clone(entryValue));
                });
                return output;
            }
            if (input instanceof Set) {
                const output = new Set();
                seen.set(input, output);
                input.forEach(function (entryValue) {
                    output.add(clone(entryValue));
                });
                return output;
            }

            const output = Array.isArray(input) ? new Array(input.length) : {};
            seen.set(input, output);
            Object.keys(input).forEach(function (key) {
                output[key] = clone(input[key]);
            });
            return output;
        }

        return clone(value);
    };
})(globalThis);
