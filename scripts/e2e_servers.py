#!/usr/bin/env python3
"""局域网传输 App 端到端测试服务器。

提供四种协议，全部绑定 127.0.0.1（Android 模拟器通过 10.0.2.2 访问宿主回环）：
  - FTP   127.0.0.1:2121  用户 test / test
  - FTPS  127.0.0.1:2122  显式 TLS (AUTH TLS)，自签名证书
  - SFTP  127.0.0.1:2222  用户 test / test
  - SCP   127.0.0.1:2222  同一 SSH 端口，exec "scp -f/-t"

用法： python servers.py [--root DIR] [--ftp-port 2121] [--ftps-port 2122] [--ssh-port 2222]
"""
import argparse
import errno
import os
import posixpath
import random
import socket
import stat
import sys
import threading
import time
import traceback
from datetime import datetime, timedelta

HOST = "127.0.0.1"
USER = "test"
PASSWORD = "test"


def log(*args):
    print("[e2e]", *args, flush=True)


# ---------------------------------------------------------------- 测试数据
def ensure_root(root_dir):
    os.makedirs(root_dir, exist_ok=True)
    for sub in ("docs", "media", "empty"):
        os.makedirs(os.path.join(root_dir, sub), exist_ok=True)
    hello = os.path.join(root_dir, "hello.txt")
    if not os.path.exists(hello):
        with open(hello, "w", encoding="utf-8") as f:
            f.write("hello from lan transfer e2e server\n" * 3)
    binary = os.path.join(root_dir, "sample.bin")
    if not os.path.exists(binary):
        with open(binary, "wb") as f:
            f.write(random.randbytes(512 * 1024))
    readme = os.path.join(root_dir, "docs", "readme.txt")
    if not os.path.exists(readme):
        with open(readme, "w", encoding="utf-8") as f:
            f.write("远程目录中的示例文档 / sample document\n")
    empty_file = os.path.join(root_dir, "empty.txt")
    if not os.path.exists(empty_file):
        open(empty_file, "wb").close()
    log("root dir ready:", root_dir)


def resolve_in_root(root_dir, remote_path):
    """把远端绝对路径限制在 root 内。"""
    normalized = posixpath.normpath(posixpath.join("/", remote_path or "/"))
    local = os.path.realpath(os.path.join(root_dir, normalized.lstrip("/")))
    root_real = os.path.realpath(root_dir)
    if not (local == root_real or local.startswith(root_real + os.sep)):
        raise PermissionError("path outside root: " + remote_path)
    return local


# ---------------------------------------------------------------- FTP / FTPS
def start_ftp(root_dir, port, masquerade=None):
    from pyftpdlib.authorizers import DummyAuthorizer
    from pyftpdlib.handlers import FTPHandler
    from pyftpdlib.ioloop import IOLoop
    from pyftpdlib.servers import FTPServer

    authorizer = DummyAuthorizer()
    authorizer.add_user(USER, PASSWORD, root_dir, perm="elradfmwMT")
    authorizer.add_anonymous(root_dir, perm="elr")

    class Handler(FTPHandler):
        pass

    from pyftpdlib.handlers.ftp.data import DTPHandler

    class ThrottledDTP(DTPHandler):
        ac_out_buffer_size = 8192

        def send(self, data):
            result = super().send(data)
            time.sleep(0.004)
            return result

    Handler.dtp_handler = ThrottledDTP
    Handler.authorizer = authorizer
    Handler.passive_ports = range(62000, 62050)
    Handler.banner = "LanTransfer E2E FTP"
    if masquerade:
        Handler.masquerade_address = masquerade
    # 每个 pyftpdlib 服务器使用独立的 IOLoop，避免与 FTPS 服务器共享事件循环
    server = FTPServer((HOST, port), Handler, ioloop=IOLoop())
    threading.Thread(target=server.serve_forever, daemon=True).start()
    log("FTP  listening on", HOST, port)
    return server


def ensure_self_signed_cert(cert_path, key_path):
    if os.path.exists(cert_path) and os.path.exists(key_path):
        return
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.x509.oid import NameOID
    import ipaddress

    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    name = x509.Name([
        x509.NameAttribute(NameOID.COMMON_NAME, "LanTransfer E2E"),
        x509.NameAttribute(NameOID.ORGANIZATION_NAME, "LanTransfer"),
    ])
    now = datetime.utcnow()
    cert = (
        x509.CertificateBuilder()
        .subject_name(name)
        .issuer_name(name)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - timedelta(days=1))
        .not_valid_after(now + timedelta(days=3650))
        .add_extension(
            x509.SubjectAlternativeName([
                x509.DNSName("localhost"),
                x509.IPAddress(ipaddress.ip_address("127.0.0.1")),
                x509.IPAddress(ipaddress.ip_address("10.0.2.2")),
            ]),
            critical=False,
        )
        .sign(key, hashes.SHA256())
    )
    with open(key_path, "wb") as f:
        f.write(key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.TraditionalOpenSSL,
            serialization.NoEncryption(),
        ))
    with open(cert_path, "wb") as f:
        f.write(cert.public_bytes(serialization.Encoding.PEM))


def start_ftps(root_dir, port, cert_path, key_path, masquerade=None):
    from pyftpdlib.authorizers import DummyAuthorizer
    from pyftpdlib.handlers import TLS_FTPHandler
    from pyftpdlib.ioloop import IOLoop
    from pyftpdlib.servers import FTPServer
    from OpenSSL import SSL as openssl_ssl

    ensure_self_signed_cert(cert_path, key_path)
    authorizer = DummyAuthorizer()
    authorizer.add_user(USER, PASSWORD, root_dir, perm="elradfmwMT")

    class Handler(TLS_FTPHandler):
        pass

    from pyftpdlib.handlers.ftps.data import TLS_DTPHandler

    class ThrottledTlsDTP(TLS_DTPHandler):
        ac_out_buffer_size = 8192

        def send(self, data):
            result = super().send(data)
            time.sleep(0.006)
            return result

    Handler.dtp_handler = ThrottledTlsDTP
    Handler.authorizer = authorizer
    Handler.certfile = cert_path
    Handler.keyfile = key_path
    Handler.tls_control_required = False
    Handler.tls_data_required = False
    # 强制 TLS1.2：pyftpdlib 2.2 + 新版 PyOpenSSL 在 TLS1.3 数据通道关闭握手时会卡在
    # _do_ssl_shutdown 循环，导致客户端上传卡死。TLS1.2 可稳定完成数据通道关闭握手。
    Handler.ssl_protocol = openssl_ssl.TLSv1_2_METHOD
    Handler.passive_ports = range(62050, 62100)
    Handler.banner = "LanTransfer E2E FTPS"
    if masquerade:
        Handler.masquerade_address = masquerade
    server = FTPServer((HOST, port), Handler, ioloop=IOLoop())
    threading.Thread(target=server.serve_forever, daemon=True).start()
    log("FTPS listening on", HOST, port, "(explicit AUTH TLS, self-signed)")
    return server



# ---------------------------------------------------------------- 最小 shell（SCP 模式用）
MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]


def _ls_long_line(local_path, name):
    full = os.path.join(local_path, name)
    st = os.lstat(full)
    perms = stat.filemode(st.st_mode)
    size = st.st_size
    mtime = time.localtime(st.st_mtime)
    six_months = 180 * 24 * 3600
    if time.time() - st.st_mtime > six_months:
        when = " %s %2d  %d" % (MONTHS[mtime.tm_mon - 1], mtime.tm_mday, mtime.tm_year)
    else:
        when = " %s %2d %02d:%02d" % (
            MONTHS[mtime.tm_mon - 1], mtime.tm_mday, mtime.tm_hour, mtime.tm_min
        )
    display = name
    if stat.S_ISLNK(st.st_mode):
        try:
            display = name + " -> " + os.readlink(full)
        except OSError:
            pass
    return "%s 1 %s %s %d%s %s" % (perms, USER, USER, size, when, display)


def run_shell(root_dir, command):
    """模拟 SCP 模式会用到的少数 shell 命令，返回 (退出码, 标准输出, 标准错误)。"""
    import shlex

    try:
        if command.startswith("LC_ALL=C ls "):
            import shlex
            args = shlex.split(command[len("LC_ALL=C ls "):])
            target = args[-1] if args else "/"
            local = resolve_in_root(root_dir, target)
            if os.path.isfile(local):
                return 0, _ls_long_line(os.path.dirname(local), os.path.basename(local)) + "\n", ""
            lines = ["total 0"]
            for name in sorted(os.listdir(local)):
                lines.append(_ls_long_line(local, name))
            return 0, "\n".join(lines) + "\n", ""
        if command.startswith("mkdir -p "):
            args = shlex.split(command[len("mkdir -p "):])
            for arg in args:
                os.makedirs(resolve_in_root(root_dir, arg), exist_ok=True)
            return 0, "", ""
        if command.startswith("rmdir "):
            args = shlex.split(command[len("rmdir "):])
            for arg in args:
                os.rmdir(resolve_in_root(root_dir, arg))
            return 0, "", ""
        if command.startswith("rm -f ") or command.startswith("rm -rf "):
            prefix = "rm -rf " if command.startswith("rm -rf ") else "rm -f "
            args = shlex.split(command[len(prefix):])
            for arg in args:
                local = resolve_in_root(root_dir, arg)
                if os.path.isdir(local) and not os.path.islink(local):
                    if prefix == "rm -rf ":
                        import shutil
                        shutil.rmtree(local)
                    else:
                        os.rmdir(local)
                else:
                    try:
                        os.remove(local)
                    except FileNotFoundError:
                        pass
            return 0, "", ""
        if command.startswith("mv "):
            args = shlex.split(command[len("mv "):])
            if len(args) >= 2:
                src = resolve_in_root(root_dir, args[0])
                dst = resolve_in_root(root_dir, args[1])
                if os.path.isdir(dst):
                    dst = os.path.join(dst, os.path.basename(src))
                os.replace(src, dst)
            return 0, "", ""
        return 127, "", "unsupported command: " + command
    except Exception as exc:  # noqa: BLE001 - 模拟 shell 的错误直接回给客户端
        return 1, "", "%s: %s" % (type(exc).__name__, exc)


# ---------------------------------------------------------------- SFTP
def build_sftp_classes():
    import paramiko

    def status_for(exc):
        code = getattr(exc, "errno", 0)
        if code == errno.ENOENT:
            return paramiko.SFTP_NO_SUCH_FILE
        if code in (errno.EACCES, errno.EPERM):
            return paramiko.SFTP_PERMISSION_DENIED
        return paramiko.SFTP_FAILURE

    class Handle(paramiko.SFTPHandle):
        def stat(self):
            try:
                return paramiko.SFTPAttributes.from_stat(os.fstat(self.readfile.fileno()))
            except OSError as exc:
                return status_for(exc)

        def chattr(self, attr):
            return paramiko.SFTP_OK

    class SftpServer(paramiko.SFTPServerInterface):
        ROOT = "/"

        def _local(self, path):
            return resolve_in_root(self.ROOT, path)

        def list_folder(self, path):
            try:
                local = self._local(path)
                out = []
                for name in sorted(os.listdir(local)):
                    full = os.path.join(local, name)
                    attr = paramiko.SFTPAttributes.from_stat(os.lstat(full))
                    attr.filename = name
                    out.append(attr)
                return out
            except OSError as exc:
                return status_for(exc)

        def _attr(self, path, use_lstat):
            try:
                local = self._local(path)
                fn = os.lstat if use_lstat else os.stat
                return paramiko.SFTPAttributes.from_stat(fn(local))
            except OSError as exc:
                return status_for(exc)

        def stat(self, path):
            return self._attr(path, False)

        def lstat(self, path):
            return self._attr(path, True)

        def open(self, path, flags, attr):
            try:
                local = self._local(path)
                mode = "rb"
                if flags & os.O_WRONLY or flags & os.O_RDWR:
                    if flags & os.O_APPEND:
                        mode = "ab"
                    elif flags & os.O_TRUNC:
                        mode = "wb"
                    elif os.path.exists(local):
                        mode = "r+b"
                    else:
                        mode = "wb"
                handle = Handle(flags)
                handle.filename = local
                handle.readfile = open(local, mode)
                handle.writefile = handle.readfile
                if attr is not None and attr.st_mode:
                    try:
                        os.chmod(local, attr.st_mode & 0o7777)
                    except OSError:
                        pass
                return handle
            except OSError as exc:
                return status_for(exc)

        def remove(self, path):
            try:
                os.remove(self._local(path))
                return paramiko.SFTP_OK
            except OSError as exc:
                return status_for(exc)

        def rename(self, oldpath, newpath):
            try:
                os.replace(self._local(oldpath), self._local(newpath))
                return paramiko.SFTP_OK
            except OSError as exc:
                return status_for(exc)

        def mkdir(self, path, attr):
            try:
                os.mkdir(self._local(path))
                return paramiko.SFTP_OK
            except OSError as exc:
                return status_for(exc)

        def rmdir(self, path):
            try:
                os.rmdir(self._local(path))
                return paramiko.SFTP_OK
            except OSError as exc:
                return status_for(exc)

        def canonicalize(self, path):
            return posixpath.normpath(posixpath.join("/", path or "/"))

    return SftpServer


# ---------------------------------------------------------------- SCP
def _read_one(channel):
    data = channel.recv(1)
    if not data:
        raise EOFError("channel closed")
    return data[0]


def _read_line(channel):
    buf = bytearray()
    while True:
        data = channel.recv(1)
        if not data:
            return None
        if data == b"\n":
            return bytes(buf)
        buf.extend(data)
        if len(buf) > 4096:
            raise IOError("scp line too long")


def scp_send(channel, root_dir, remote_path):
    local = resolve_in_root(root_dir, remote_path)
    if os.path.isdir(local):
        channel.sendall(b"\x01scp: directories are not supported by this test server\n")
        raise IOError("directory download unsupported")
    size = os.path.getsize(local)
    mode = stat.S_IMODE(os.stat(local).st_mode)
    name = os.path.basename(local)
    channel.sendall(f"C{mode:04o} {size} {name}\n".encode())
    if _read_one(channel) != 0:
        raise IOError("client rejected header")
    with open(local, "rb") as f:
        while True:
            chunk = f.read(65536)
            if not chunk:
                break
            channel.sendall(chunk)
    channel.sendall(b"\x00")
    if _read_one(channel) != 0:
        raise IOError("client rejected data")
    channel.sendall(b"\x00")
    log("SCP download done:", remote_path, size, "bytes")


def scp_receive(channel, root_dir, dest_path):
    dest = resolve_in_root(root_dir, dest_path)
    if not os.path.isdir(dest):
        os.makedirs(dest, exist_ok=True)
    # SCP sink 启动时先发一个 ACK，通知 source 可以发送 C 记录（OpenSSH sink.c 行为）
    channel.sendall(b"\x00")
    while True:
        line = _read_line(channel)
        if line is None or line == b"":
            break
        if line[0] == 0:
            continue
        command = line.decode("utf-8", "replace")
        if command.startswith("C"):
            parts = command[1:].split(" ", 2)
            if len(parts) != 3:
                raise IOError("bad C line: " + command)
            mode_s, size_s, name = parts
            size = int(size_s)
            channel.sendall(b"\x00")
            target = os.path.join(dest, os.path.basename(name))
            with open(target, "wb") as f:
                remaining = size
                while remaining > 0:
                    chunk = channel.recv(min(65536, remaining))
                    if not chunk:
                        raise EOFError("upload interrupted")
                    f.write(chunk)
                    remaining -= len(chunk)
            if _read_one(channel) != 0:
                raise IOError("client checksum ack missing")
            channel.sendall(b"\x00")
            log("SCP upload done:", target, size, "bytes")
        elif command.startswith("D"):
            parts = command[1:].split(" ", 2)
            name = parts[2] if len(parts) == 3 else "sub"
            os.makedirs(os.path.join(dest, os.path.basename(name)), exist_ok=True)
            dest = os.path.join(dest, os.path.basename(name))
            channel.sendall(b"\x00")
        elif command.startswith("E"):
            dest = os.path.dirname(dest) or dest
            channel.sendall(b"\x00")
        elif command.startswith("T"):
            channel.sendall(b"\x00")
        else:
            raise IOError("unexpected scp command: " + repr(command))


def start_ssh(root_dir, port, host_key_path):
    import paramiko

    SftpServer = build_sftp_classes()
    SftpServer.ROOT = root_dir

    if os.path.exists(host_key_path):
        host_key = paramiko.RSAKey(filename=host_key_path)
    else:
        host_key = paramiko.RSAKey.generate(2048)
        host_key.write_private_key_file(host_key_path)

    class ServerInterface(paramiko.ServerInterface):
        def check_auth_password(self, username, password):
            if username == USER and password == PASSWORD:
                return paramiko.AUTH_SUCCESSFUL
            return paramiko.AUTH_FAILED

        def get_allowed_auths(self, username):
            return "password"

        def check_channel_request(self, kind, chanid):
            if kind == "session":
                return paramiko.OPEN_SUCCEEDED
            return paramiko.OPEN_FAILED_ADMINISTRATIVELY_PROHIBITED

        def check_channel_subsystem_request(self, channel, name):
            if name == "sftp":
                return super().check_channel_subsystem_request(channel, name)
            return False

        def check_channel_exec_request(self, channel, command):
            text = command.decode("utf-8", "replace").strip()
            threading.Thread(
                target=self._handle_exec, args=(channel, text), daemon=True
            ).start()
            return True

        def _handle_exec(self, channel, text):
            try:
                import shlex

                log("SSH exec:", text)
                argv = shlex.split(text)
                if len(argv) >= 3 and argv[0] == "scp" and argv[1] == "-f":
                    scp_send(channel, root_dir, argv[2])
                elif len(argv) >= 3 and argv[0] == "scp" and argv[1] == "-t":
                    scp_receive(channel, root_dir, argv[2])
                elif argv and argv[0] == "scp":
                    channel.sendall(b"\x01scp: unsupported arguments\n")
                else:
                    code, out, err = run_shell(root_dir, text)
                    if out:
                        channel.sendall(out.encode("utf-8"))
                    if err:
                        channel.sendall(err.encode("utf-8"))
                    try:
                        channel.send_exit_status(code)
                    except Exception:
                        pass
            except Exception:
                log("exec failed:", traceback.format_exc())
            finally:
                try:
                    channel.send_exit_status(0)
                except Exception:
                    pass
                time.sleep(0.2)
                try:
                    channel.close()
                except Exception:
                    pass

    def handle_client(client_sock):
        transport = None
        try:
            transport = paramiko.Transport(client_sock)
            transport.add_server_key(host_key)
            transport.set_subsystem_handler("sftp", paramiko.SFTPServer, SftpServer)
            transport.start_server(server=ServerInterface())
        except Exception:
            log("ssh client failed:", traceback.format_exc())
            if transport is not None:
                transport.close()

    server_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server_sock.bind((HOST, port))
    server_sock.listen(16)

    def accept_loop():
        while True:
            try:
                client, addr = server_sock.accept()
            except OSError:
                break
            threading.Thread(target=handle_client, args=(client,), daemon=True).start()

    threading.Thread(target=accept_loop, daemon=True).start()
    log("SFTP/SCP listening on", HOST, port)
    return server_sock


def start_raw_tcp(root_dir, port, chunk_delay=0.0):
    """裸 TCP 文件通道：连接后发送 sample.bin 全部字节再关闭，用于排查网络层问题。

    chunk_delay > 0 时每 8KB 休眠该秒数，用于给模拟器网络降速（模拟器高速流会丢字节）。
    """
    payload = open(os.path.join(root_dir, "sample.bin"), "rb").read()
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind((HOST, port))
    sock.listen(8)

    def serve_one(client):
        try:
            if chunk_delay > 0:
                chunk = 8192
                for offset in range(0, len(payload), chunk):
                    client.sendall(payload[offset:offset + chunk])
                    time.sleep(chunk_delay)
            else:
                client.sendall(payload)
        except OSError as exc:
            log("raw tcp send failed:", exc)
        finally:
            try:
                client.shutdown(socket.SHUT_WR)
                client.close()
            except OSError:
                pass

    def loop():
        while True:
            try:
                client, _ = sock.accept()
            except OSError:
                break
            threading.Thread(target=serve_one, args=(client,), daemon=True).start()

    threading.Thread(target=loop, daemon=True).start()
    log("RAW TCP listening on", HOST, port, "payload", len(payload), "bytes")
    return sock


def main():
    base_dir = os.path.dirname(os.path.abspath(__file__))
    default_root = os.path.join(os.path.dirname(base_dir), "build", "e2e-root")
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default=default_root)
    parser.add_argument("--ftp-port", type=int, default=2121)
    parser.add_argument("--ftps-port", type=int, default=2122)
    parser.add_argument("--ssh-port", type=int, default=2222)
    parser.add_argument("--raw-port", type=int, default=7777)
    parser.add_argument("--debug", action="store_true")
    parser.add_argument(
        "--masquerade",
        default=None,
        help="FTP/FTPS PASV 响应的地址；模拟器测试传 10.0.2.2",
    )
    args = parser.parse_args()

    if args.debug:
        import logging

        logging.basicConfig(level=logging.DEBUG, format="%(levelname)s %(message)s")
        logging.getLogger("paramiko").setLevel(logging.WARNING)
        logging.getLogger("pyftpdlib").setLevel(logging.DEBUG)

    root_dir = os.path.abspath(args.root)
    ensure_root(root_dir)
    cert_path = os.path.join(root_dir, ".e2e-cert.pem")
    key_path = os.path.join(root_dir, ".e2e-key.pem")
    host_key_path = os.path.join(root_dir, ".e2e-host-rsa")

    start_ftp(root_dir, args.ftp_port, args.masquerade)
    start_ftps(root_dir, args.ftps_port, cert_path, key_path, args.masquerade)
    start_ssh(root_dir, args.ssh_port, host_key_path)
    start_raw_tcp(root_dir, args.raw_port)
    start_raw_tcp(root_dir, args.raw_port + 1, chunk_delay=0.002)

    print()
    print("=" * 64)
    print(f"FTP   : {USER}/{PASSWORD} @ 10.0.2.2:{args.ftp_port}")
    print(f"FTPS  : {USER}/{PASSWORD} @ 10.0.2.2:{args.ftps_port} (explicit TLS)")
    print(f"SFTP  : {USER}/{PASSWORD} @ 10.0.2.2:{args.ssh_port}")
    print(f"SCP   : {USER}/{PASSWORD} @ 10.0.2.2:{args.ssh_port}")
    print(f"RAW   : raw tcp sample.bin @ 10.0.2.2:{args.raw_port} (full speed)")
    print(f"RAW2  : raw tcp sample.bin @ 10.0.2.2:{args.raw_port + 1} (throttled ~4MB/s)")
    print(f"ROOT  : {root_dir}")
    print("=" * 64, flush=True)

    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        log("shutting down")


if __name__ == "__main__":
    sys.exit(main())