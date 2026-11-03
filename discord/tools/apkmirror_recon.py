#!/usr/bin/env python3
"""Recon helper: dump links from an APKMirror page through a real Chromium.

APKMirror sits behind a Cloudflare managed challenge that plain HTTP clients
cannot pass. This drives the bundled Chromium (Playwright) so the challenge is
solved, then prints the page title plus every anchor whose href matches an
optional substring filter. Used to discover release/download page URLs.

Usage:
    apkmirror_recon.py <url> [href-substring-filter] [--text]

Environment:
    HELIUM_BIN   path to a Chromium binary (default:
    /opt/helium-browser-bin/chrome)
"""

import os
import sys

from playwright.sync_api import sync_playwright

CHROME = os.environ.get("HELIUM_BIN", "/opt/helium-browser-bin/chrome")
UA = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36"
)


def main() -> int:
    args = [a for a in sys.argv[1:] if a not in ("--text", "--headed")]
    want_text = "--text" in sys.argv
    # Cloudflare's managed challenge clears far more reliably when the browser
    # renders for real. Set APKMIRROR_HEADED=1 (needs $DISPLAY) to fall back.
    headed = "--headed" in sys.argv or os.environ.get("APKMIRROR_HEADED") == "1"
    if not args:
        print(__doc__, file=sys.stderr)
        return 2
    url = args[0]
    flt = args[1] if len(args) > 1 else ""

    with sync_playwright() as p:
        browser = p.chromium.launch(
            executable_path=CHROME,
            headless=not headed,
            args=[
                "--no-sandbox",
                "--disable-blink-features=AutomationControlled",
            ],
        )
        ctx = browser.new_context(user_agent=UA)
        page = ctx.new_page()
        print(f"[recon] opening {url}", flush=True)
        page.goto(url, wait_until="domcontentloaded", timeout=120_000)
        # Cloudflare's managed challenge lands on an interstitial titled
        # "Just a moment..." and clears itself after the JS proof-of-work.
        for _ in range(24):
            if "just a moment" not in page.title().lower():
                break
            page.wait_for_timeout(5_000)
        try:
            page.wait_for_load_state("networkidle", timeout=30_000)
        except Exception:
            pass
        print(f"[recon] title: {page.title()}", flush=True)
        print(f"[recon] url: {page.url}", flush=True)

        seen = set()
        anchors = page.eval_on_selector_all(
            "a",
            "els => els.map(e => [e.getAttribute('href')||'', "
            "(e.textContent||'').trim().replace(/\\s+/g,' ')])",
        )
        for href, text in anchors:
            if flt and flt not in href:
                continue
            if not href or href in seen:
                continue
            seen.add(href)
            if want_text:
                print(f"{href}\t{text[:120]}")
            else:
                print(f"{href}\t{text[:120]}")
        browser.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
