#!/usr/bin/env python3
"""宿主侧端到端测试：以另一台设备的身份连接 Android App 内启动的本机服务器。

覆盖四种协议：FTP / FTPS（显式）/ SFTP / SCP，均做「列目录 + 下载校验 + 上传」。
前置：模拟器里 App 已启动本机服务器（ServerModeHostTestBridge），并做 adb forward：
  adb -s <serial> forward tcp:2121 tcp:2121
  adb -s <serial> forward tcp:2122 tcp:2122
  adb -s <serial> forward tcp:2222 tcp:2222

用法：
  python scripts/server_mode_hosttest.py --host 127.0.0.1 --ftp-port 2121 --ftps-port 2122 --ssh-port 2222 --user test --password test
"""
import argparse
import ftplib
import io
import os
import re
import socket
import ssl
import subprocess
import sys

ADB_SERIAL = None
ADB_PATH = "adb"


def adb_forward(port):
    """把宿主机端口透传到模拟器内相同端口（PASV 数据连接用）。"""
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
    """通过 adb forward 访问模拟器内 FTP 被动数据端口。"""

    def makepasv(self):
        host, port = super().makepasv()
        adb_forward(port)
        return "127.0.0.1", port


class AdbFTP(AdbPasvMixin, ftplib.FTP):
    pass


class AdbFTPS(AdbPasvMixin, ftplib.FTP_TLS):
    pass

HELLO_TEXT = b"hello from android server\n"
SAMPLE_EXPECTED = bytes([i % 251 for i in range(256 * 1024)])


class Result:
    def __init__(self):
        self.passed = []
        self.failed = []

    def check(self, name, fn):
        try:
            detail = fn()
            self.passed.append(name)
            print(f"PASS  {name}  ({detail})", flush=True)
        except Exception as exc:  # noqa: BLE001
            self.failed.append((name, repr(exc)))
            print(f"FAIL  {name}  -> {exc!r}", flush=True)
            import traceback

            traceback.print_exc()

    @property
    def ok(self):
        return not self.failed


def test_ftp(args):
    client = AdbFTP()
    client.connect(args.host, args.ftp_port, timeout=20)
    client.login(args.user, args.password)
    names = client.nlst()
    if args.minimal:
        payload = os.urandom(32 * 1024)
        client.storbinary("STOR release-check-ftp.bin", io.BytesIO(payload))
        back = io.BytesIO()
        client.retrbinary("RETR release-check-ftp.bin", back.write)
        if back.getvalue() != payload:
            raise AssertionError("FTP 上传回读不一致")
        client.delete("release-check-ftp.bin")
        client.quit()
        return f"minimal mode list={len(names)}, upload/download roundtrip ok"
    if "hello.txt" not in names:
        raise AssertionError("根目录缺少 hello.txt: " + repr(names))
    got = io.BytesIO()
    client.retrbinary("RETR hello.txt", got.write)
    if got.getvalue() != HELLO_TEXT:
        raise AssertionError("hello.txt 内容不一致")
    if "sample.bin" in names:
        sample = io.BytesIO()
        client.retrbinary("RETR sample.bin", sample.write)
        if sample.getvalue() != SAMPLE_EXPECTED:
            raise AssertionError("sample.bin 下载内容不一致")
    payload = os.urandom(48 * 1024)
    client.storbinary("STOR upload-ftp.bin", io.BytesIO(payload))
    back = io.BytesIO()
    client.retrbinary("RETR upload-ftp.bin", back.write)
    if back.getvalue() != payload:
        raise AssertionError("FTP 上传回读不一致")
    client.quit()
    return f"list={len(names)}, download ok, upload {len(payload)}B roundtrip ok"


def test_ftps(args):
    ctx = ssl._create_unverified_context()
    client = AdbFTPS(context=ctx)
    client.connect(args.host, args.ftps_port, timeout=20)
    client.auth()
    client.login(args.user, args.password)
    client.prot_p()
    names = client.nlst()
    if args.minimal:
        payload = os.urandom(24 * 1024)
        client.storbinary("STOR release-check-ftps.bin", io.BytesIO(payload))
        back = io.BytesIO()
        client.retrbinary("RETR release-check-ftps.bin", back.write)
        if back.getvalue() != payload:
            raise AssertionError("FTPS 上传回读不一致")
        client.delete("release-check-ftps.bin")
        client.quit()
        return f"minimal explicit TLS list={len(names)}, upload/download roundtrip ok"
    if "hello.txt" not in names:
        raise AssertionError("根目录缺少 hello.txt")
    got = io.BytesIO()
    client.retrbinary("RETR hello.txt", got.write)
    if got.getvalue() != HELLO_TEXT:
        raise AssertionError("hello.txt 内容不一致")
    payload = os.urandom(32 * 1024)
    client.storbinary("STOR upload-ftps.bin", io.BytesIO(payload))
    back = io.BytesIO()
    client.retrbinary("RETR upload-ftps.bin", back.write)
    if back.getvalue() != payload:
        raise AssertionError("FTPS 上传回读不一致")
    client.quit()
    return f"explicit TLS list={len(names)}, download ok, upload {len(payload)}B roundtrip ok"


def ssh_transport(args):
    import paramiko

    sock = socket.create_connection((args.host, args.ssh_port), timeout=20)
    transport = paramiko.Transport(sock)
    transport.start_client(timeout=20)
    transport.auth_password(args.user, args.password)
    return transport


def test_sftp(args):
    transport = ssh_transport(args)
    sftp = transport.open_sftp_client()
    names = sftp.listdir("/")
    if args.minimal:
        payload = os.urandom(48 * 1024)
        with sftp.open("/release-check-sftp.bin", "wb") as f:
            f.write(payload)
        with sftp.open("/release-check-sftp.bin", "rb") as f:
            if f.read() != payload:
                raise AssertionError("SFTP 上传回读不一致")
        sftp.remove("/release-check-sftp.bin")
        sftp.close()
        transport.close()
        return f"minimal mode list={len(names)}, upload/download roundtrip ok"
    if "hello.txt" not in names:
        raise AssertionError("根目录缺少 hello.txt: " + repr(names))
    with sftp.open("/hello.txt", "rb") as f:
        if f.read() != HELLO_TEXT:
            raise AssertionError("hello.txt 内容不一致")
    if "sample.bin" in names:
        with sftp.open("/sample.bin", "rb") as f:
            if f.read() != SAMPLE_EXPECTED:
                raise AssertionError("sample.bin 下载内容不一致")
    payload = os.urandom(64 * 1024)
    with sftp.open("/upload-sftp.bin", "wb") as f:
        f.write(payload)
    with sftp.open("/upload-sftp.bin", "rb") as f:
        if f.read() != payload:
            raise AssertionError("SFTP 上传回读不一致")
    sftp.close()
    transport.close()
    return f"list={len(names)}, download ok, upload {len(payload)}B roundtrip ok"


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
    if not line.startswith(b"C"):
        raise IOError("bad scp header: " + repr(line))
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


def test_scp(args):
    transport = ssh_transport(args)
    listing = run_shell(transport, "LC_ALL=C ls -la '/'")
    if args.minimal:
        payload = os.urandom(20 * 1024)
        scp_upload(transport, "/", "release-check-scp.bin", payload)
        if scp_download(transport, "/release-check-scp.bin") != payload:
            raise AssertionError("SCP 上传回读不一致")
        run_shell(transport, "rm -f '/release-check-scp.bin'")
        transport.close()
        return "minimal mode ls -la ok, scp upload/download roundtrip ok"
    if "hello.txt" not in listing:
        raise AssertionError("SCP 模式 ls 输出缺少 hello.txt: " + listing[:300])
    if scp_download(transport, "/hello.txt") != HELLO_TEXT:
        raise AssertionError("SCP 下载 hello.txt 内容不一致")
    payload = os.urandom(40 * 1024)
    scp_upload(transport, "/", "upload-scp.bin", payload)
    if scp_download(transport, "/upload-scp.bin") != payload:
        raise AssertionError("SCP 上传回读不一致")
    run_shell(transport, "mkdir -p '/scp-dir'")
    run_shell(transport, "mv '/upload-scp.bin' '/scp-dir/moved.bin'")
    moved = run_shell(transport, "LC_ALL=C ls -la '/scp-dir'")
    if "moved.bin" not in moved:
        raise AssertionError("SCP mv 后目录中未找到 moved.bin: " + moved[:300])
    run_shell(transport, "rm -f '/scp-dir/moved.bin'")
    run_shell(transport, "rmdir '/scp-dir'")
    # 保留一个 SCP 上传文件供设备端断言"另一台设备上传的文件出现在共享目录"
    scp_upload(transport, "/", "upload-scp-keep.bin", os.urandom(24 * 1024))
    transport.close()
    return "ls -la ok, scp -f/-t roundtrip ok, mkdir/mv/rm/rmdir ok"


def main():
    global ADB_SERIAL, ADB_PATH
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", default="adb", help="adb 可执行文件路径")
    parser.add_argument("--adb-serial", default=None, help="模拟器序列号，用于转发 PASV 数据端口")
    parser.add_argument("--minimal", action="store_true", help="无预置文件的最小自证模式（release 验证用）")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--ftp-port", type=int, default=2121)
    parser.add_argument("--ftps-port", type=int, default=2122)
    parser.add_argument("--ssh-port", type=int, default=2222)
    parser.add_argument("--user", default="test")
    parser.add_argument("--password", default="test")
    args = parser.parse_args()
    ADB_SERIAL = args.adb_serial
    ADB_PATH = args.adb

    result = Result()
    result.check("FTP", lambda: test_ftp(args))
    result.check("FTPS", lambda: test_ftps(args))
    result.check("SFTP", lambda: test_sftp(args))
    result.check("SCP", lambda: test_scp(args))

    print()
    print(f"RESULT: {len(result.passed)}/{len(result.passed) + len(result.failed)} protocols ok")
    if not result.ok:
        for name, err in result.failed:
            print(f"  failed: {name}: {err}")
    return 0 if result.ok else 1


if __name__ == "__main__":
    sys.exit(main())