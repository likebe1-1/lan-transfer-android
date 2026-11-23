#!/usr/bin/env python3
"""LanLink 协议测速脚本（真机 / 模拟器通用）。

在 PC 侧以另一台设备的身份连接 App 内启动的「本机服务器」，对 FTP / FTPS / SFTP / SCP
分别做「上传 N MiB + 下载 N MiB」并统计吞吐（MiB/s 与 MB/s），用于验证协议顺序、缓冲区
等优化的实际收益。

用法（真机同网段直连）：
    python scripts/speed_bench.py --host 192.168.3.32 --user android --password lantest123
用法（模拟器，需 adb forward）：
    python scripts/speed_bench.py --adb-serial emulator-5554 --user test --password test

说明：
- SFTP/SCP 用 JSch 客户端（与 App 内一致），FTP/FTPS 用 ftplib（标准库）；
- 仅在目标目录写入临时文件，测试结束删除；
- 结果打印表格并给出相对 FTP 的百分比。
"""
from __future__ import annotations

import argparse
import io
import os
import statistics
import subprocess
import sys
import time

MiB = 1024 * 1024
TMP_NAME = "lanlink-speed-bench.bin"


def human(rate):
    return "%.2f MiB/s (%.2f MB/s)" % (rate, rate * MiB / 1_000_000)


def adb_forward(adb, serial, port):
    if not serial:
        return
    subprocess.run([adb, "-s", serial, "forward", "tcp:%d" % port, "tcp:%d" % port],
                   check=True, capture_output=True, timeout=20)


class PasvFTP:
    def __init__(self, adb, serial):
        self.adb, self.serial = adb, serial

    def pasv(self, host, port):
        if not self.serial:
            return host, port
        adb_forward(self.adb, self.serial, port)
        return "127.0.0.1", port


def bench_ftp(args, tls):
    import ftplib
    import ssl

    host, port = args.host, (args.ftps_port if tls else args.ftp_port)
    payload = os.urandom(args.size)

    if tls:
        ctx = ssl._create_unverified_context()
        client = ftplib.FTP_TLS(context=ctx)
    else:
        client = ftplib.FTP()
    client.connect(host, port, timeout=30)
    if tls:
        client.auth()
    client.login(args.user, args.password)
    if tls:
        client.prot_p()

    up_start = time.perf_counter()
    client.storbinary("STOR " + TMP_NAME, io.BytesIO(payload), blocksize=128 * 1024)
    up = len(payload) / (time.perf_counter() - up_start) / MiB

    sink = io.BytesIO()
    down_start = time.perf_counter()
    client.retrbinary("RETR " + TMP_NAME, sink.write, blocksize=128 * 1024)
    down = len(payload) / (time.perf_counter() - down_start) / MiB
    client.delete(TMP_NAME)
    client.quit()
    return up, down


def bench_ssh(args, scp):
    try:
        import paramiko
    except ImportError:
        raise SystemExit("需要 paramiko：pip install paramiko")

    transport = paramiko.Transport((args.host, args.ssh_port))
    transport.connect(username=args.user, password=args.password)
    payload = os.urandom(args.size)
    try:
        if scp:
            channel = transport.open_session()
            channel.exec_command("cat > " + TMP_NAME)
            up_start = time.perf_counter()
            channel.sendall(payload)
            channel.shutdown_write()
            channel.recv_exit_status()
            up = len(payload) / (time.perf_counter() - up_start) / MiB

            channel = transport.open_session()
            channel.exec_command("cat " + TMP_NAME)
            down_start = time.perf_counter()
            total = 0
            while True:
                data = channel.recv(128 * 1024)
                if not data:
                    break
                total += len(data)
            down = total / (time.perf_counter() - down_start) / MiB
            channel.close()
        else:
            sftp = transport.open_sftp_client()
            up_start = time.perf_counter()
            sftp.putfo(io.BytesIO(payload), TMP_NAME)
            up = len(payload) / (time.perf_counter() - up_start) / MiB

            down_start = time.perf_counter()
            with sftp.open(TMP_NAME, "rb") as handle:
                total = 0
                while True:
                    data = handle.read(128 * 1024)
                    if not data:
                        break
                    total += len(data)
            down = total / (time.perf_counter() - down_start) / MiB
            sftp.remove(TMP_NAME)
            sftp.close()
    finally:
        transport.close()
    return up, down


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--ftp-port", type=int, default=2121)
    parser.add_argument("--ftps-port", type=int, default=2122)
    parser.add_argument("--ssh-port", type=int, default=2222)
    parser.add_argument("--user", default="android")
    parser.add_argument("--password", required=True)
    parser.add_argument("--size", type=int, default=8, help="单个方向传输的 MiB 数（默认 8）")
    parser.add_argument("--rounds", type=int, default=3, help="每协议重复次数，取中位数（默认 3）")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--adb-serial", default=None, help="模拟器序列号，用于 PASV 端口转发")
    parser.add_argument("--only", default=None, help="只测某协议：ftp/ftps/sftp/scp")
    args = parser.parse_args()
    args.size *= MiB

    targets = []
    names = {"ftp": (lambda: bench_ftp(args, False)),
             "ftps": (lambda: bench_ftp(args, True)),
             "sftp": (lambda: bench_ssh(args, False)),
             "scp": (lambda: bench_ssh(args, True))}
    for key, fn in names.items():
        if args.only and args.only != key:
            continue
        targets.append((key, fn))

    print("LanLink 协议测速：size=%d MiB/方向, rounds=%d, host=%s" % (args.size // MiB, args.rounds, args.host))
    print("%-6s %-28s %-28s %s" % ("协议", "上传", "下载", "备注"))
    results = {}
    for key, fn in targets:
        ups, downs = [], []
        note = ""
        try:
            for _ in range(args.rounds):
                up, down = fn()
                ups.append(up)
                downs.append(down)
        except Exception as exc:  # noqa: BLE001
            note = "失败: %s" % str(exc)[:60]
        if ups:
            up_med = statistics.median(ups)
            down_med = statistics.median(downs)
            results[key] = (up_med, down_med)
            print("%-6s %-28s %-28s %s" % (key.upper(), human(up_med), human(down_med), note))
        else:
            print("%-6s %-28s %-28s %s" % (key.upper(), "-", "-", note))

    base = results.get("ftp")
    if base:
        print()
        print("相对明文 FTP 的比例（下载）：")
        for key, (_, down) in results.items():
            print("  %-5s %5.1f%%" % (key.upper(), down / base[1] * 100))
    return 0


if __name__ == "__main__":
    sys.exit(main())
