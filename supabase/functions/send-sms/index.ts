// Mamoot — Supabase "Send SMS" Auth Hook (Edge Function)
//
// Supabase Auth generates and verifies the phone OTP; this function only
// DELIVERS it through an Iranian SMS gateway so codes reach +98 numbers
// (Supabase's built-in providers cannot deliver to Iran).
//
// Required function secrets:
//   SEND_SMS_HOOK_SECRET  "v1,whsec_..." — must match the Send SMS hook config
// Optional (set these to go live; without them the function runs in DEV mode
// and only prints the OTP to the function's logs for manual testing):
//   SMS_PROVIDER   "kavenegar" (default) | "ghasedak" | "melipayamak"
//   SMS_API_KEY    provider API key (kavenegar / ghasedak)
//   SMS_TEMPLATE   kavenegar: verify template name (default "mamoot-otp"),
//                  melipayamak: numeric pattern bodyId
//   SMS_USERNAME / SMS_PASSWORD   melipayamak panel credentials
//   SMS_SENDER     ghasedak sender line number
//
// Only Iranian mobile numbers (+98) are accepted — an anti-abuse guard so a
// leaked publishable key can never be used for international SMS pumping.

import { Webhook } from "https://esm.sh/standardwebhooks@1.0.0";

type HookPayload = {
  user?: { phone?: string };
  sms?: { otp?: string };
};

function hookError(httpCode: number, message: string): Response {
  return new Response(
    JSON.stringify({ error: { http_code: httpCode, message } }),
    { status: 200, headers: { "Content-Type": "application/json" } },
  );
}

function ok(): Response {
  return new Response("{}", {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

/** "989123456789" -> "09123456789" (local format Iranian gateways expect). */
function toLocal(digits: string): string {
  return digits.startsWith("98") ? "0" + digits.slice(2) : digits;
}

async function postForm(url: string, fields: Record<string, string>, headers: Record<string, string> = {}): Promise<string> {
  const res = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded", ...headers },
    body: new URLSearchParams(fields).toString(),
    signal: AbortSignal.timeout(8000),
  });
  return await res.text();
}

async function sendViaKavenegar(local: string, otp: string): Promise<void> {
  const apiKey = Deno.env.get("SMS_API_KEY") ?? "";
  const template = Deno.env.get("SMS_TEMPLATE") ?? "mamoot-otp";
  const text = await postForm(
    `https://api.kavenegar.com/v1/${encodeURIComponent(apiKey)}/verify/lookup.json`,
    { receptor: local, token: otp, template },
  );
  const status = JSON.parse(text)?.return?.status;
  if (status !== 200) throw new Error(`kavenegar status=${status}`);
}

async function sendViaGhasedak(local: string, otp: string): Promise<void> {
  const apiKey = Deno.env.get("SMS_API_KEY") ?? "";
  const sender = Deno.env.get("SMS_SENDER") ?? "";
  const text = await postForm(
    "https://api.ghasedak.me/v2/sms/send/simple",
    { sender, receptor: local, message: `کد ورود مموت شما: ${otp}` },
    { apikey: apiKey },
  );
  const code = JSON.parse(text)?.result?.code;
  if (code !== 200) throw new Error(`ghasedak code=${code}`);
}

async function sendViaMelipayamak(local: string, otp: string): Promise<void> {
  const res = await fetch("https://rest.payamak-panel.com/api/SendSMS/BaseServiceNumber", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      username: Deno.env.get("SMS_USERNAME") ?? "",
      password: Deno.env.get("SMS_PASSWORD") ?? "",
      to: local,
      text: [otp],
      bodyId: Number(Deno.env.get("SMS_TEMPLATE") ?? "0"),
    }),
    signal: AbortSignal.timeout(8000),
  });
  const json = await res.json();
  if (!json?.Value || String(json.Value).length < 5) {
    throw new Error(`melipayamak RetStatus=${json?.RetStatus}`);
  }
}

Deno.serve(async (req: Request): Promise<Response> => {
  if (req.method !== "POST") return new Response("not allowed", { status: 405 });

  const hookSecret = (Deno.env.get("SEND_SMS_HOOK_SECRET") ?? "").replace("v1,whsec_", "");
  if (!hookSecret) {
    console.error("SEND_SMS_HOOK_SECRET is not set");
    return hookError(500, "SMS service is not configured");
  }

  const payload = await req.text();
  const headers = Object.fromEntries(req.headers);
  let event: HookPayload;
  try {
    event = new Webhook(hookSecret).verify(payload, headers) as HookPayload;
  } catch (_e) {
    return new Response("invalid signature", { status: 401 });
  }

  const phoneRaw = event?.user?.phone ?? "";
  const otp = event?.sms?.otp ?? "";
  const digits = phoneRaw.replace(/[^0-9]/g, "");
  if (!otp || !(digits.startsWith("98") && digits.length === 12)) {
    return hookError(400, "Only Iranian (+98) mobile numbers are supported");
  }
  const local = toLocal(digits);

  const provider = (Deno.env.get("SMS_PROVIDER") ?? "kavenegar").toLowerCase();
  const hasCredentials = provider === "melipayamak"
    ? !!(Deno.env.get("SMS_USERNAME") && Deno.env.get("SMS_PASSWORD"))
    : !!Deno.env.get("SMS_API_KEY");

  if (!hasCredentials) {
    // DEV/TEST MODE: no SMS panel connected yet. The project owner can read
    // the code from this function's logs to test the login flow end-to-end.
    // As soon as provider credentials are set, OTPs are never logged.
    console.log(`[DEV ONLY - no SMS provider] login code for ${local}: ${otp}`);
    return ok();
  }

  try {
    if (provider === "ghasedak") await sendViaGhasedak(local, otp);
    else if (provider === "melipayamak") await sendViaMelipayamak(local, otp);
    else await sendViaKavenegar(local, otp);
    console.log(`OTP SMS sent via ${provider} to ${local}`);
    return ok();
  } catch (e) {
    console.error(`SMS send failed (${provider}):`, e);
    return hookError(500, "SMS delivery failed, please try again");
  }
});
