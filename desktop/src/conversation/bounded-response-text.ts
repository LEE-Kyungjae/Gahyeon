export const MAXIMUM_CONVERSATION_RESPONSE_CHARACTERS = 128 * 1024

export function appendBoundedResponseText(
  current: string,
  delta: string,
  maximumCharacters = MAXIMUM_CONVERSATION_RESPONSE_CHARACTERS,
) {
  if (!Number.isSafeInteger(maximumCharacters) || maximumCharacters < 1) {
    throw new RangeError('maximum response characters must be a positive safe integer')
  }
  if (current.length > maximumCharacters - delta.length) {
    throw new RangeError('conversation response exceeds renderer capacity')
  }
  return current + delta
}
