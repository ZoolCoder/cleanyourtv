import PAGE from "./page.html";

let cached: { body: string; csp: string } | null = null;

async function hash(text: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return "'sha256-" + btoa(String.fromCharCode(...new Uint8Array(digest))) + "'";
}

function inner(tag: string): string {
  const open = PAGE.indexOf(`<${tag}>`);
  const close = PAGE.indexOf(`</${tag}>`, open);
  if (open < 0 || close < 0) throw new Error(`page has no <${tag}>`);
  return PAGE.slice(open + tag.length + 2, close);
}

/** The send page, and a CSP that allows only its own inline style and script, by hash. */
export async function page(): Promise<{ body: string; csp: string }> {
  if (cached) return cached;
  const csp = [
    "default-src 'none'",
    `script-src ${await hash(inner("script"))}`,
    `style-src ${await hash(inner("style"))}`,
    "connect-src 'self'",
    "img-src 'self'",
    "base-uri 'none'",
    "form-action 'none'",
    "frame-ancestors 'none'",
  ].join("; ");
  cached = { body: PAGE, csp };
  return cached;
}
