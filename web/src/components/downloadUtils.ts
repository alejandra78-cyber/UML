// Helper compartido por los componentes que descargan un archivo generado por el
// backend como respuesta de un fetch (Generar Backend / Exportar XMI): parseo del
// nombre sugerido en el header Content-Disposition y disparo de la descarga vía
// blob + <a download> temporal, patrón estándar del navegador.

/** Extrae el filename de un header `Content-Disposition: attachment; filename="x.zip"` (o filename*=UTF-8''x.zip). */
export function parseContentDispositionFilename(header: string | null): string | null {
  if (!header) return null

  const extended = header.match(/filename\*=(?:UTF-8''|utf-8'')?([^;]+)/i)
  if (extended) {
    const raw = extended[1].trim().replace(/^"|"$/g, '')
    try {
      return decodeURIComponent(raw)
    } catch {
      return raw
    }
  }

  const simple = header.match(/filename="?([^";]+)"?/i)
  return simple ? simple[1].trim() : null
}

/** Dispara la descarga de un Blob como archivo, sin dejar el <a> temporal en el DOM ni la URL en memoria. */
export function downloadBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(url)
}
