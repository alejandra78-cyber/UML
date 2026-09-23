// `crypto.randomUUID()` solo existe en "secure contexts" (HTTPS o localhost,
// spec de la Web Crypto API) -- en una build servida por HTTP plano (posible
// en producción según cómo se despliegue) es `undefined` y cualquier llamada
// revienta con un TypeError. Los ids que genera este módulo se usan como
// UUID de negocio (id de clase/atributo/método/relación/mutación encolada),
// no con fines criptográficos, así que no hace falta la calidad de entropía
// de la Crypto API -- un generador basado en Math.random() alcanza y
// funciona en cualquier contexto.
export function generateUUID(): string {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (char) => {
    const random = (Math.random() * 16) | 0
    const value = char === 'x' ? random : (random & 0x3) | 0x8
    return value.toString(16)
  })
}
