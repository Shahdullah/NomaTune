function normalizedToken(value) {
  const normalized = typeof value === "string" ? value.trim() : "";
  return normalized || undefined;
}

export function applyGvsPoToken(url, options) {
  if (url.searchParams.get("sabr") === "1") return url;
  if (!options.supportsGvsPoToken) {
    url.searchParams.delete("pot");
    return url;
  }

  const videoPoToken = normalizedToken(options.videoPoToken);
  if (videoPoToken) {
    url.searchParams.set("pot", videoPoToken);
    return url;
  }

  const sessionPoToken = normalizedToken(options.sessionPoToken);
  if (sessionPoToken && !url.searchParams.has("pot")) {
    url.searchParams.set("pot", sessionPoToken);
  }
  return url;
}
