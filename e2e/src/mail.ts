// What the instance mailed, read from the run's mail relay (the overlay's Mailpit, `stack().mailApi`) through its HTTP
// API — from the test process, never from a page: the relay is not the instance, so no page may reach it (egress.ts),
// and Node's own requests are not a page's. The relay keeps everything in memory for the run, so a reader filters by
// recipient and subject, and counts.
import { expect } from '@playwright/test'
import { stack } from './stack'

interface Summary {
  ID: string
  To: { Address: string }[]
  Subject: string
}

export interface Mail {
  id: string
  to: string[]
  subject: string
  text: string
  attachments: { partId: string; fileName: string; contentType: string }[]
}

async function api<T>(path: string): Promise<T> {
  const response = await fetch(`${stack().mailApi}${path}`)
  if (!response.ok) throw new Error(`the mail relay answered ${response.status} to ${path}`)
  return (await response.json()) as T
}

/** Every message the relay holds whose recipients include [to] and whose subject matches [subject], oldest first. */
export async function mailsTo(to: string, subject: RegExp): Promise<Mail[]> {
  const { messages } = await api<{ messages: Summary[] }>('/api/v1/messages?limit=200')
  const matching = messages.filter((m) => m.To.some((r) => r.Address === to) && subject.test(m.Subject)).reverse()
  return Promise.all(matching.map(async (m) => {
    const full = await api<{ Text: string; Attachments: { PartID: string; FileName: string; ContentType: string }[] }>(
      `/api/v1/message/${m.ID}`)
    return {
      id: m.ID,
      to: m.To.map((r) => r.Address),
      subject: m.Subject,
      text: full.Text,
      attachments: full.Attachments.map((a) => ({ partId: a.PartID, fileName: a.FileName, contentType: a.ContentType })),
    }
  }))
}

/** One attachment of [mail], decoded, as text — what a mail client hands a calendar. */
export async function attachmentText(mail: Mail, partId: string): Promise<string> {
  const response = await fetch(`${stack().mailApi}/api/v1/message/${mail.id}/part/${partId}`)
  if (!response.ok) throw new Error(`the mail relay answered ${response.status} to part ${partId} of ${mail.id}`)
  return response.text()
}

/** Waits until exactly [count] such messages arrived, and returns them. */
export async function awaitMails(to: string, subject: RegExp, count = 1): Promise<Mail[]> {
  let found: Mail[] = []
  await expect.poll(async () => (found = await mailsTo(to, subject)).length,
    { message: `${count} mail(s) to ${to} with a subject matching ${subject}`, timeout: 60_000 }).toBe(count)
  return found
}
