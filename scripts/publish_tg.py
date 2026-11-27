import argparse
import json
import mimetypes
import os
import urllib.request
import uuid


def _multipart_request(url: str, fields: dict, files: list[tuple[str, str]]) -> dict:
    boundary = uuid.uuid4().hex
    body = bytearray()

    for key, value in fields.items():
        body.extend(f"--{boundary}\r\n".encode("utf-8"))
        body.extend(f'Content-Disposition: form-data; name="{key}"\r\n\r\n'.encode("utf-8"))
        body.extend(f"{value}\r\n".encode("utf-8"))

    for field_name, file_path in files:
        filename = os.path.basename(file_path)
        mime_type = mimetypes.guess_type(filename)[0] or "application/octet-stream"
        body.extend(f"--{boundary}\r\n".encode("utf-8"))
        body.extend(
            f'Content-Disposition: form-data; name="{field_name}"; filename="{filename}"\r\n'.encode("utf-8")
        )
        body.extend(f"Content-Type: {mime_type}\r\n\r\n".encode("utf-8"))
        with open(file_path, "rb") as f:
            body.extend(f.read())
        body.extend(b"\r\n")

    body.extend(f"--{boundary}--\r\n".encode("utf-8"))

    req = urllib.request.Request(
        url,
        data=bytes(body),
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=120) as resp:
        return json.loads(resp.read().decode("utf-8"))


def send_message(token: str, chat_id: str, text: str) -> dict:
    url = f"https://api.telegram.org/bot{token}/sendMessage"
    data = json.dumps({"chat_id": chat_id, "text": text, "parse_mode": "HTML"}).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8"))


def send_documents(token: str, chat_id: str, files: list[str], caption: str = "") -> dict:
    if len(files) == 1:
        url = f"https://api.telegram.org/bot{token}/sendDocument"
        fields = {"chat_id": chat_id}
        if caption:
            fields["caption"] = caption
            fields["parse_mode"] = "HTML"
        return _multipart_request(url, fields, [("document", files[0])])

    url = f"https://api.telegram.org/bot{token}/sendMediaGroup"
    media = []
    file_tuples = []
    for idx, path in enumerate(files):
        attach_name = f"file{idx}"
        item = {"type": "document", "media": f"attach://{attach_name}"}
        if idx == len(files) - 1 and caption:
            item["caption"] = caption
            item["parse_mode"] = "HTML"
        media.append(item)
        file_tuples.append((attach_name, path))

    fields = {"chat_id": chat_id, "media": json.dumps(media)}
    return _multipart_request(url, fields, file_tuples)


def main():
    parser = argparse.ArgumentParser(description="Publish release notes and APKs to Telegram channel.")
    parser.add_argument("--token", default=os.environ.get("TG_BOT_TOKEN", ""), help="Telegram Bot Token")
    parser.add_argument("--channel", default="@Byedpirussia", help="Target channel username or chat_id")
    parser.add_argument("--text", default="", help="Post text / caption (HTML)")
    parser.add_argument("--text-file", default="", help="Path to UTF-8 file with post text")
    parser.add_argument("--photo", default="", help="Path to image file to send via sendPhoto")
    parser.add_argument("--files", nargs="*", default=[], help="Paths to APK files to attach")
    parser.add_argument("--proxy", default="http://127.0.0.1:10809", help="HTTP/HTTPS proxy URL")
    args = parser.parse_args()

    if args.text_file:
        with open(args.text_file, "r", encoding="utf-8") as f:
            args.text = f.read().strip()

    if args.proxy:
        opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({"http": args.proxy, "https": args.proxy})
        )
        urllib.request.install_opener(opener)

    if not args.token:
        raise SystemExit("Error: --token or TG_BOT_TOKEN is required")

    if args.photo:
        url = f"https://api.telegram.org/bot{args.token}/sendPhoto"
        fields = {"chat_id": args.channel}
        if args.text:
            fields["caption"] = args.text
            fields["parse_mode"] = "HTML"
        res = _multipart_request(url, fields, [("photo", args.photo)])
    elif args.files:
        res = send_documents(args.token, args.channel, args.files, args.text)
    elif args.text:
        res = send_message(args.token, args.channel, args.text)
    else:
        raise SystemExit("Error: provide --text or --files")

    print(json.dumps(res, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
