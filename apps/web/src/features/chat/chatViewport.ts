export const shouldHydrateMessageHistory = (
  selectedId: string | null,
  streaming: boolean,
): selectedId is string =>
  Boolean(selectedId) && !streaming

export const hasMessagesBelow = (
  scrollHeight: number,
  scrollTop: number,
  clientHeight: number,
  threshold = 12,
) => scrollHeight - scrollTop - clientHeight > threshold
