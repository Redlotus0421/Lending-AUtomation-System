/**
 * Open a URL that is only known after an async call (a signed URL, a generated PDF) in a new tab.
 *
 * Browsers only allow window.open during the click itself; Safari (and iOS) treat a call made after
 * an `await` as an unrequested pop-up and silently block it. So the tab is opened synchronously and
 * pointed at the URL once it arrives. If the tab could not be opened at all, the current page
 * navigates instead. On error the empty tab is closed and the error is rethrown for the caller.
 */
export async function openInNewTab(getUrl: () => Promise<string>): Promise<void> {
  const w = window.open('', '_blank')
  if (w) w.opener = null
  try {
    const url = await getUrl()
    if (w) w.location.href = url
    else window.location.href = url
  } catch (e) {
    w?.close()
    throw e
  }
}
