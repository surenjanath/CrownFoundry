#!/usr/bin/env python3
"""Render ``docs/PRIVACY.md`` into ``docs/privacy.html`` for GitHub Pages.

Google Play needs a privacy policy at a URL that renders as a readable page. GitHub Pages is
served with ``.nojekyll`` here — deliberately, so there is no build step that can fail silently
and leave the policy 404ing — which means Markdown is delivered as plain text rather than turned
into HTML. So the page is generated here instead, and ``PRIVACY.md`` stays the single source of
truth.

    python tools/build_privacy_page.py

The converter handles exactly the Markdown this document uses: headings, paragraphs, bullet and
numbered lists, pipe tables, fenced code, horizontal rules, and inline code/bold/italic/links. It
is not a general Markdown implementation and does not try to be — if the policy grows a construct
it does not know, add it here rather than hand-editing the HTML.
"""

from __future__ import annotations

import html
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SOURCE = ROOT / "docs" / "PRIVACY.md"
TARGET = ROOT / "docs" / "privacy.html"

SHELL = """<!doctype html>
<html lang="en"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>CrownFoundry Privacy Policy</title>
<meta name="description" content="Privacy policy for the CrownFoundry Android app.">
<link rel="icon" href="play-store/graphics/play-icon-512.png">
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Fraunces:opsz,wght@9..144,600&family=Public+Sans:wght@400;500;600&display=swap">
<style>
 :root{--ground:#F6F4EE;--surface:#FFF;--surface-2:#EFEBE0;--ink:#16191A;--ink-2:#474E4C;
   --ink-3:#777E7B;--rule:#DCD7C9;--accent:#9A4A08;
   --display:"Fraunces","Iowan Old Style",Georgia,serif;--body:"Public Sans","Helvetica Neue",Arial,sans-serif;}
 @media (prefers-color-scheme:dark){:root{--ground:#10130F;--surface:#171B16;--surface-2:#1E241C;
   --ink:#EDE9DF;--ink-2:#B0B6AB;--ink-3:#7D847A;--rule:#2C332A;--accent:#F9A55C;}}
 *{box-sizing:border-box}
 body{margin:0;background:var(--ground);color:var(--ink);font-family:var(--body);font-size:17px;
   line-height:1.65;-webkit-font-smoothing:antialiased}
 .wrap{max-width:44rem;margin:0 auto;padding:3.5rem 1.4rem 5rem}
 h1,h2,h3{font-family:var(--display);font-weight:600;text-wrap:balance}
 h1{font-size:clamp(2rem,5vw,2.7rem);line-height:1.05;letter-spacing:-.02em;margin:0 0 1.2rem}
 h2{font-size:1.35rem;margin:2.4rem 0 .8rem;padding-bottom:.5rem;border-bottom:1px solid var(--rule)}
 h3{font-size:1.05rem;margin:1.6rem 0 .5rem}
 p,li{color:var(--ink-2)} p{margin:0 0 .9rem}
 ul,ol{margin:0 0 1rem;padding-left:1.2rem;display:flex;flex-direction:column;gap:.4rem}
 strong{color:var(--ink)}
 code{font-family:ui-monospace,Menlo,monospace;font-size:.86em;background:var(--surface-2);
   padding:.1em .35em;border-radius:3px;word-break:break-word}
 pre{background:var(--surface-2);border:1px solid var(--rule);border-radius:4px;padding:.9rem 1.05rem;
   overflow-x:auto;margin:0 0 1rem}
 pre code{background:none;padding:0;font-size:.84rem;line-height:1.6}
 hr{border:0;border-top:1px solid var(--rule);margin:2rem 0}
 .tablewrap{overflow-x:auto;border:1px solid var(--rule);border-radius:4px;margin:0 0 1.2rem}
 table{border-collapse:collapse;width:100%;background:var(--surface);font-size:.93rem}
 th,td{text-align:left;padding:.6rem .85rem;border-bottom:1px solid var(--rule);vertical-align:top}
 th{background:var(--surface-2);color:var(--ink-3);font-size:.76rem;letter-spacing:.06em;
   text-transform:uppercase;white-space:nowrap}
 tbody tr:last-child td{border-bottom:none}
 a{color:var(--accent);text-underline-offset:2px;word-break:break-word}
 .back{display:inline-block;margin-bottom:2rem;font-size:.9rem}
</style></head>
<body><div class="wrap">
<a class="back" href="./">&larr; CrownFoundry</a>
{body}
</div></body></html>
"""

BLOCK_START = re.compile(r"^(#|\||-\s|\d+\.\s|```|---)")


def inline(text: str) -> str:
    """Inline Markdown, escaped first so the policy can never inject markup."""
    out = html.escape(text)
    out = re.sub(r"`([^`]+)`", r"<code>\1</code>", out)
    out = re.sub(r"\*\*([^*]+)\*\*", r"<strong>\1</strong>", out)
    out = re.sub(r"\*([^*]+)\*", r"<em>\1</em>", out)
    out = re.sub(r"(?<![\w>])((?:https?://|mailto:)[^\s<)]+)", r'<a href="\1">\1</a>', out)
    # A bare address in the text is the one people actually click.
    out = re.sub(r"(?<![\w.@-])([\w.+-]+@[\w-]+\.[\w.]+)(?![\w@-])", r'<a href="mailto:\1">\1</a>', out)
    return out


def convert(markdown: str) -> str:
    lines = markdown.split("\n")
    out: list[str] = []
    i = 0
    while i < len(lines):
        line = lines[i]

        if line.startswith("```"):
            block = []
            i += 1
            while i < len(lines) and not lines[i].startswith("```"):
                block.append(html.escape(lines[i]))
                i += 1
            out.append("<pre><code>" + "\n".join(block) + "</code></pre>")

        elif line.startswith("### "):
            out.append(f"<h3>{inline(line[4:])}</h3>")
        elif line.startswith("## "):
            out.append(f"<h2>{inline(line[3:])}</h2>")
        elif line.startswith("# "):
            out.append(f"<h1>{inline(line[2:])}</h1>")
        elif line.strip() == "---":
            out.append("<hr>")

        elif line.startswith("|"):
            rows = []
            while i < len(lines) and lines[i].startswith("|"):
                rows.append(lines[i])
                i += 1
            i -= 1
            cells = [[c.strip() for c in r.strip().strip("|").split("|")] for r in rows]
            body = [r for r in cells if not all(set(c) <= set("-: ") for c in r)]
            head, rest = body[0], body[1:]
            parts = ["<div class='tablewrap'><table><thead><tr>"]
            parts += [f"<th>{inline(c)}</th>" for c in head]
            parts.append("</tr></thead><tbody>")
            for row in rest:
                parts.append("<tr>" + "".join(f"<td>{inline(c)}</td>" for c in row) + "</tr>")
            parts.append("</tbody></table></div>")
            out.append("".join(parts))

        elif re.match(r"^\d+\.\s", line):
            items: list[str] = []
            while i < len(lines) and (re.match(r"^\d+\.\s", lines[i]) or lines[i].startswith("   ")):
                if re.match(r"^\d+\.\s", lines[i]):
                    items.append(re.sub(r"^\d+\.\s", "", lines[i]))
                elif lines[i].strip():
                    items[-1] += " " + lines[i].strip()
                i += 1
            i -= 1
            out.append("<ol>" + "".join(f"<li>{inline(x)}</li>" for x in items) + "</ol>")

        elif line.startswith("- "):
            items = []
            while i < len(lines) and lines[i].startswith("- "):
                items.append(lines[i][2:])
                i += 1
            i -= 1
            out.append("<ul>" + "".join(f"<li>{inline(x)}</li>" for x in items) + "</ul>")

        elif line.strip():
            para = [line]
            while i + 1 < len(lines) and lines[i + 1].strip() and not BLOCK_START.match(lines[i + 1]):
                i += 1
                para.append(lines[i])
            out.append("<p>" + inline(" ".join(p.strip() for p in para)) + "</p>")

        i += 1
    return "\n".join(out)


def main() -> int:
    if not SOURCE.exists():
        print(f"missing {SOURCE}", file=sys.stderr)
        return 1
    page = SHELL.replace("{body}", convert(SOURCE.read_text()))
    TARGET.write_text(page)
    print(f"wrote {TARGET.relative_to(ROOT)} ({len(page):,} bytes) from {SOURCE.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
