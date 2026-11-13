#!/usr/bin/env python3
"""验证 e2e_servers.py 四种协议服务端本身工作正常。"""
import ftplib
import hashlib
import io
import os
import socket
import ssl
import sys
import time

HOST = "127.0.0.1"
USER = "test"
PASSWORD = "test"
FTP_PORT = 2121
FTPS_PORT = 2122
SSH_PORT = 2222

results = []


def check(name, fn):
    t0 = time.time()
    try:
        detail = fn()
        results.append((True, name, detail, time.time() - t0))
        print(f"PASS  {name}  ({detail})", flush=True)
    except Exception as exc:
        results.append((False, name, repr(exc), time.time() - t0))
        print(f"FAIL  {name}  -> {exc!r}", flush=True)
        import traceback
        traceback.print_exc()


def test_ftp():
    client = ftplib.FTP()
    client.connect(HOST, FTP_PORT, timeout=15)
    client.login(USER, PASSWORD)
    names = client.nlst()
    assert "hello.txt" in names, names
    buf = io.BytesIO()
    client.retrbinary("RETR hello.txt", buf.write)
    assert buf.getvalue().startswith(b"hello from lan transfer")
    payload = os.urandom(64 * 1024)
    client.storbinary("STOR ftp_upload.bin", io.BytesIO(payload))
    buf2 = io.BytesIO()
    client.retrbinary("RETR ftp_upload.bin", buf2.write)
    assert buf2.getvalue() == payload
    client.quit()
    return f"list={len(names)} files, upload/download {len(payload)}B ok"


def test_ftps():
    ctx = ssl._create_unverified_context()
    client = ftplib.FTP_TLS(context=ctx)
    client.connect(HOST, FTPS_PORT, timeout=15)
    client.auth()
    client.login(USER, PASSWORD)
    client.prot_p()
    names = client.nlst()
    assert "hello.txt" in names, names
    buf = io.BytesIO()
    client.retrbinary("RETR hello.txt", buf.write)
    assert buf.getvalue().startswith(b"hello from lan transfer")
    payload = os.urandom(32 * 1024)
    client.storbinary("STOR ftps_upload.bin", io.BytesIO(payload))
    buf2 = io.BytesIO()
    client.retrbinary("RETR ftps_upload.bin", buf2.write)
    assert buf2.getvalue() == payload
    client.quit()
    return f"explicit TLS ok, list={len(names)}, upload/download {len(payload)}B ok"


def _ssh_transport():
    import paramiko
    sock = socket.create_connection((HOST, SSH_PORT), timeout=15)
    transport = paramiko.Transport(sock)
    transport.start_client(timeout=15)
    transport.auth_password(USER, PASSWORD)
    return transport


def test_sftp():
    transport = _ssh_transport()
    sftp = transport.open_sftp_client()
    names = sftp.listdir("/")
    assert "hello.txt" in names, names
    with sftp.open("/hello.txt", "rb") as f:
        data = f.read()
    assert data.startswith(b"hello from lan transfer")
    payload = os.urandom(128 * 1024)
    with sftp.open("/sftp_upload.bin", "wb") as f:
        f.write(payload)
    with sftp.open("/sftp_upload.bin", "rb") as f:
        assert f.read() == payload
    unique_dir = "/sftp_dir_%d" % int(time.time() * 1000 % 1000000)
    sftp.mkdir(unique_dir)
    sftp.rename("/sftp_upload.bin", unique_dir + "/moved.bin")
    st = sftp.stat(unique_dir + "/moved.bin")
    assert st.st_size == len(payload)
    sftp.close()
    transport.close()
    return f"list={len(names)}, upload/download {len(payload)}B, mkdir/rename ok"


def _read_exact(channel, n):
    buf = b""
    while len(buf) < n:
        chunk = channel.recv(n - len(buf))
        if not chunk:
            raise EOFError("channel closed")
        buf += chunk
    return buf


def _scp_from(transport, remote_path):
    """模拟 JSch ScpFrom：scp -f 下载。"""
    channel = transport.open_session()
    channel.exec_command(f"scp -f {remote_path}")
    line = b""
    while not line.endswith(b"\n"):
        line += _read_exact(channel, 1)
    assert line.startswith(b"C"), line
    mode_s, size_s, name = line[1:].decode().strip().split(" ")
    size = int(size_s)
    channel.sendall(b"\x00")
    data = _read_exact(channel, size)
    ack = _read_exact(channel, 1)
    assert ack == b"\x00", ack
    channel.sendall(b"\x00")
    channel.close()
    return name, data


def _scp_to(transport, remote_dir, name, payload):
    """模拟 JSch ScpTo：scp -t 上传。"""
    channel = transport.open_session()
    channel.exec_command(f"scp -t {remote_dir}")
    channel.sendall(f"C0644 {len(payload)} {name}\n".encode())
    ack = _read_exact(channel, 1)
    assert ack == b"\x00", ack
    channel.sendall(payload)
    channel.sendall(b"\x00")
    ack = _read_exact(channel, 1)
    assert ack == b"\x00", ack
    channel.close()


def test_scp():
    transport = _ssh_transport()
    name, data = _scp_from(transport, "/hello.txt")
    assert data.startswith(b"hello from lan transfer")
    payload = os.urandom(96 * 1024)
    _scp_to(transport, "/", "scp_upload.bin", payload)
    name2, data2 = _scp_from(transport, "/scp_upload.bin")
    assert data2 == payload, (len(data2), len(payload))
    transport.close()
    return f"download {len(data)}B, upload/download {len(payload)}B ok"


check("FTP", test_ftp)
check("FTPS (explicit TLS)", test_ftps)
check("SFTP", test_sftp)
check("SCP", test_scp)

failed = [r for r in results if not r[0]]
print()
print(f"RESULT: {len(results) - len(failed)}/{len(results)} protocols ok")
sys.exit(1 if failed else 0)