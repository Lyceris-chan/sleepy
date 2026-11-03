#!/usr/bin/env python3
"""Download an APKMirror release through a real Chromium (Playwright).

APKMirror sits behind a Cloudflare managed challenge that plain HTTP clients
(and headless Chrome with --dump-dom) cannot pass. Driving a real Chromium via
Playwright solves the challenge, after which the download URL can be fetched
with the browser context's own cookie jar.

Usage:
    apkmirror_dl.py <apkmirror-download-page-url> <outdir>

Environment:
    HELIUM_BIN   path to a Chromium binary (default:
    /opt/helium-browser-bin/chrome)
"""

import os
import re
import sys

from playwright.sync_api import sync_playwright

CHROME = os.environ.get("HELIUM_BIN", "/opt/helium-browser-bin/chrome")
UA = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36"
)


def main() -> int:
    if len(sys.argv) < 3:
        print(__doc__, file=sys.stderr)
        return 2
    url, outdir = sys.argv[1], sys.argv[2]
    os.makedirs(outdir, exist_ok=True)

    with sync_playwright() as p:
        browser = p.chromium.launch(
            executable_path=CHROME,
            headless=True,
            args=[
                "--no-sandbox",
                "--disable-blink-features=AutomationControlled",
            ],
        )
        ctx = browser.new_context(user_agent=UA, accept_downloads=True)
        page = ctx.new_page()
        print(f"[fetch] opening {url}", flush=True)
        page.goto(url, wait_until="domcontentloaded", timeout=120_000)
        page.wait_for_selector("a.downloadButton", timeout=60_000)

        href = page.get_attribute("a.downloadButton", "href")
        dl_url = (
            "https://www.apkmirror.com" + href if href.startswith("/") else href
        )
        print(f"[fetch] download url: {dl_url}", flush=True)

        resp = ctx.request.get(dl_url, timeout=1_800_000)
        if resp.status != 200:
            print(
                f"[fetch] unexpected status body: {resp.text()[:400]}",
                file=sys.stderr,
            )
            return 1

        # The /download/?key= endpoint returns an interstitial HTML page whose
        # only real payload is a link to download.php, which streams the file.
        if resp.headers.get("content-type", "").startswith("text/html"):
            m = re.search(
                r"/wp-content/themes/APKMirror/download\.php\?[^\"'<> ]+",
                resp.text(),
            )
            if not m:
                print(
                    "[fetch] no download.php link in interstitial",
                    file=sys.stderr,
                )
                return 1
            dl_url = "https://www.apkmirror.com" + m.group(0)
            print(f"[fetch] real file url: {dl_url}", flush=True)
            resp = ctx.request.get(dl_url, timeout=1_800_000)
            if resp.status != 200:
                print(f"[fetch] file status {resp.status}", file=sys.stderr)
                return 1

        print(
            f"[fetch] status={resp.status} "
            f"content-length={resp.headers.get('content-length')}",
            flush=True,
        )
        body = resp.body()
        cd = resp.headers.get("content-disposition", "")
        m = re.search(r'filename="?([^";]+)"?', cd)
        name = (
            m.group(1)
            if m
            else os.path.basename(dl_url.split("?")[0]) or "download.bin"
        )
        out = os.path.join(outdir, name)
        with open(out, "wb") as f:
            f.write(body)
        print(f"[fetch] SAVED {out} ({os.path.getsize(out)} bytes)", flush=True)
        browser.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
