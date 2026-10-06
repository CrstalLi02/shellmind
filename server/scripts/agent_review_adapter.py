#!/usr/bin/env python3
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path


def read_request():
    payload = sys.stdin.read()
    try:
        request = json.loads(payload)
    except json.JSONDecodeError as exc:
        raise ValueError(f"invalid stdin JSON: {exc}") from exc
    for field in ("task_id", "prompt", "workspace"):
        if field not in request:
            raise ValueError(f"stdin JSON missing {field}")
    return request


def post_json(url, token, body):
    request = urllib.request.Request(
        url,
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            **({"Authorization": f"Bearer {token}"} if token else {}),
        },
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=10) as response:
        return json.loads(response.read().decode("utf-8"))


def resolve_permission(base_url, token, permission):
    try:
        post_json(
            f"{base_url}/api/v1/permission/resolve",
            token,
            {
                "confirmId": permission.get("confirmId"),
                "approved": True,
                "modifiedArgs": permission.get("toolArgs"),
            },
        )
        return permission.get("confirmId")
    except Exception as exc:
        print(f"permission approval failed: {exc}", file=sys.stderr)
        return None


def stream_agent(base_url, token, task_id, prompt, workspace):
    request = urllib.request.Request(
        f"{base_url}/api/v1/chat_stream",
        data=json.dumps(
            {
                "agentId": os.environ.get("SHELLMIND_AGENT_ID", "200000"),
                "userId": os.environ.get("SHELLMIND_USER_ID", "agent-review"),
                "sessionId": f"review-{task_id}",
                "message": prompt,
                "projectContext": {
                    "name": task_id,
                    "rootPath": str(Path(workspace).resolve()),
                },
            },
            ensure_ascii=False,
        ).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            "Accept": "application/x-ndjson",
            **({"Authorization": f"Bearer {token}"} if token else {}),
        },
        method="POST",
    )

    events = []
    tool_calls = []
    errors = []
    permission_approvals = []
    final_content = ""
    done = False

    with urllib.request.urlopen(request, timeout=int(os.environ.get("SHELLMIND_REQUEST_TIMEOUT", "580"))) as response:
        for raw_line in response:
            line = raw_line.decode("utf-8").strip()
            if not line:
                continue
            try:
                event = json.loads(line)
            except json.JSONDecodeError:
                continue
            events.append(event)

            event_type = event.get("event")
            if event_type == "permission_confirm" and event.get("permission"):
                confirm_id = resolve_permission(base_url, token, event["permission"])
                if confirm_id:
                    permission_approvals.append(confirm_id)
            elif event_type == "tool_call":
                tool_calls.append(
                    {
                        "toolName": event.get("toolName"),
                        "args": event.get("args"),
                        "status": event.get("status"),
                    }
                )
            elif event_type == "text" and event.get("fullText"):
                final_content = event["fullText"]
            elif event_type == "done":
                done = True
                raw_content = event.get("content")
                try:
                    parsed = json.loads(raw_content)
                    final_content = parsed.get("content", final_content)
                except (TypeError, json.JSONDecodeError):
                    final_content = raw_content or final_content
            elif event_type == "error":
                errors.append(event.get("content") or "unknown error")

    return {
        "task_id": task_id,
        "prompt": prompt,
        "workspace": str(Path(workspace).resolve()),
        "ok": done and not errors,
        "done": done,
        "content": final_content,
        "tool_calls": tool_calls,
        "tool_call_count": len(tool_calls),
        "permission_approvals": permission_approvals,
        "event_count": len(events),
        "errors": errors,
    }


def main():
    try:
        request = read_request()
        base_url = os.environ.get("SHELLMIND_BASE_URL", "http://127.0.0.1:18091").rstrip("/")
        token = os.environ.get("SHELLMIND_RUNTIME_TOKEN")
        result = stream_agent(
            base_url,
            token,
            str(request["task_id"]),
            str(request["prompt"]),
            request["workspace"],
        )
        print(json.dumps(result, ensure_ascii=False))
        return 0 if result["ok"] else 1
    except Exception as exc:
        print(f"agent adapter failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
