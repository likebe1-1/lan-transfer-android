#!/usr/bin/env python3
"""快捷传输宿主端到端测试：读取设备写出的 quick_session.json，
以「另一台设备」身份通过 adb forward 用 FTP/FTPS/SFTP/SCP 连接 App 的快捷会话服务端，
下载并校验文件，再用四种协议各上传一个文件（验证互传）。

用法：
  python scripts/quick_mode_hosttest.py --session-json quick_session.json --adb-serial <serial> --adb <adb路径>
"""
import argparse
import ftplib
import hashlib
import io
import json
import os
import socket
import ssl
import subprocess
import sys

ADB_SERIAL = None
ADB_PATH = "adb"


def adb_forward(port):
    if not ADB_SERIAL:
        return
    result = subprocess.run(
        [ADB_PATH, "-s", ADB_SERIAL, "forward", "tcp:%d" % port, "tcp:%d" % port],
        capture_output=True,
        timeout=20,
    )
    if result.returncode != 0:
        raise IOError("adb forward 失败: " + result.stderr.decode("utf-8", "replace"))


class AdbPasvMixin:
    def makepasv(self):
        _host, port = super().makepasv()
        adb_forward(port)
        return "127.0.0.1", port


class AdbFTP(AdbPasvMixin, ftplib.FTP):
    pass


class AdbFTPS(AdbPasvMixin, ftplib.FTP_TLS):
    pass


def expected_files(session):
    return {item["name"]: item for item in session["files"]}


def verify_download(name, data, expected):
    item = expected.get(name)
    if item is None:
        raise AssertionError("发现未预期文件: " + name)
    if len(data) != item["size"]:
        raise AssertionError(name + " 大小不一致: " + str(len(data)) + " != " + str(item["size"]))
    digest = hashlib.sha256(data).hexdigest()
    if digest != item["sha256"]:
        raise AssertionError(name + " sha256 不一致")


def test_ftp(session):
    client = AdbFTP()
    client.connect("127.0.0.1", session["ftpPort"], timeout=20)
    client.login(session["username"], session["password"])
    names = set(client.nlst())
    for name in expected_files(session):
        if name not in names:
            raise AssertionError("FTP 根目录缺少 " + name)
        buf = io.BytesIO()
        client.retrbinary("RETR " + name, buf.write)
        verify_download(name, buf.getvalue(), expected_files(session))
    payload = os.urandom(48 * 1024)
    client.storbinary("STOR upload-ftp.bin", io.BytesIO(payload))
    back = io.BytesIO()
    client.retrbinary("RETR upload-ftp.bin", back.write)
    if back.getvalue() != payload:
        raise AssertionError("FTP 上传回读不一致")
    client.quit()
    return "download " + str(len(expected_files(session))) + " files verified, upload roundtrip ok"


def test_ftps(session):
    ctx = ssl._create_unverified_context()
    client = AdbFTPS(context=ctx)
    client.connect("127.0.0.1", session["ftpsPort"], timeout=20)
    client.auth()
    client.login(session["username"], session["password"])
    client.prot_p()
    names = set(client.nlst())
    for name in expected_files(session):
        if name not in names:
            raise AssertionError("FTPS 根目录缺少 " + name)
        buf = io.BytesIO()
        client.retrbinary("RETR " + name, buf.write)
        verify_download(name, buf.getvalue(), expected_files(session))
    payload = os.urandom(32 * 1024)
    client.storbinary("STOR upload-ftps.bin", io.BytesIO(payload))
    back = io.BytesIO()
    client.retrbinary("RETR upload-ftps.bin", back.write)
    if back.getvalue() != payload:
        raise AssertionError("FTPS 上传回读不一致")
    client.quit()
    return "explicit TLS download verified, upload roundtrip ok"


def ssh_transport(session):
    import paramiko

    sock = socket.create_connection(("127.0.0.1", session["sshPort"]), timeout=20)
    transport = paramiko.Transport(sock)
    transport.start_client(timeout=20)
    transport.auth_password(session["username"], session["password"])
    return transport


def test_sftp(session):
    transport = ssh_transport(session)
    sftp = transport.open_sftp_client()
    names = set(sftp.listdir("/"))
    for name in expected_files(session):
        if name not in names:
            raise AssertionError("SFTP 根目录缺少 " + name)
        with sftp.open("/" + name, "rb") as f:
            verify_download(name, f.read(), expected_files(session))
    payload = os.urandom(64 * 1024)
    with sftp.open("/upload-sftp.bin", "wb") as f:
        f.write(payload)
    with sftp.open("/upload-sftp.bin", "rb") as f:
        if f.read() != payload:
            raise AssertionError("SFTP 上传回读不一致")
    sftp.close()
    transport.close()
    return "download verified, upload roundtrip ok"


def read_exact(channel, size):
    data = b""
    while len(data) < size:
        chunk = channel.recv(size - len(data))
        if not chunk:
            raise EOFError("channel closed")
        data += chunk
    return data


def scp_download(transport, remote_path):
    channel = transport.open_session()
    channel.exec_command("scp -f " + remote_path)
    channel.sendall(b"\x00")
    line = b""
    while True:
        byte = read_exact(channel, 1)
        if byte == b"\x00":
            continue
        line = byte
        break
    while not line.endswith(b"\n"):
        line += read_exact(channel, 1)
    parts = line[1:].decode().strip().split(" ")
    size = int(parts[1])
    channel.sendall(b"\x00")
    data = read_exact(channel, size)
    if read_exact(channel, 1) != b"\x00":
        raise IOError("missing data ack")
    channel.sendall(b"\x00")
    channel.close()
    return data


def scp_upload(transport, remote_dir, name, payload):
    channel = transport.open_session()
    channel.exec_command("scp -t " + remote_dir)
    if read_exact(channel, 1) != b"\x00":
        raise IOError("sink did not ack")
    channel.sendall(("C0644 %d %s\n" % (len(payload), name)).encode())
    if read_exact(channel, 1) != b"\x00":
        raise IOError("sink rejected header")
    channel.sendall(payload)
    channel.sendall(b"\x00")
    if read_exact(channel, 1) != b"\x00":
        raise IOError("sink rejected data")
    channel.close()


def run_shell(transport, command):
    channel = transport.open_session()
    channel.exec_command(command)
    out = channel.makefile("rb", -1).read()
    channel.close()
    return out.decode("utf-8", "replace")


def test_scp(session):
    transport = ssh_transport(session)
    listing = run_shell(transport, "LC_ALL=C ls -la '/'")
    for name in expected_files(session):
        if name not in listing:
            raise AssertionError("SCP ls 输出缺少 " + name)
        verify_download(name, scp_download(transport, "/" + name), expected_files(session))
    payload = os.urandom(40 * 1024)
    scp_upload(transport, "/", "upload-scp.bin", payload)
    if scp_download(transport, "/upload-scp.bin") != payload:
        raise AssertionError("SCP 上传回读不一致")
    transport.close()
    return "ls -la + scp -f/-t verified, upload roundtrip ok"


def main():
    global ADB_SERIAL, ADB_PATH
    parser = argparse.ArgumentParser()
    parser.add_argument("--session-json", required=True)
    parser.add_argument("--adb-serial", default=None)
    parser.add_argument("--adb", default="adb")
    args = parser.parse_args()
    ADB_SERIAL = args.adb_serial
    ADB_PATH = args.adb
    with open(args.session_json, "r", encoding="utf-8") as f:
        session = json.load(f)

    tests = [
        ("FTP", lambda: test_ftp(session)),
        ("FTPS", lambda: test_ftps(session)),
        ("SFTP", lambda: test_sftp(session)),
        ("SCP", lambda: test_scp(session)),
    ]
    passed = 0
    failed = []
    for name, fn in tests:
        try:
            detail = fn()
            passed += 1
            print("PASS  " + name + "  (" + detail + ")", flush=True)
        except Exception as exc:  # noqa: BLE001
            failed.append((name, repr(exc)))
            print("FAIL  " + name + "  -> " + repr(exc), flush=True)
            import traceback

            traceback.print_exc()

    print()
    print("RESULT: " + str(passed) + "/" + str(len(tests)) + " protocols ok")
    for name, err in failed:
        print("  failed: " + name + ": " + err)
    return 0 if not failed else 1


if __name__ == "__main__":
    sys.exit(main())
