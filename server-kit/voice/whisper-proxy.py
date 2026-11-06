#!/usr/bin/env python3
"""Wyoming TCP failover proxy: :10300 -> the GPU box (KIT_HOST:KIT_PORT) if reachable, else a local engine (LOCAL_HOST:LOCAL_PORT, default 127.0.0.1:10301).

Env: LISTEN_PORT (10300), KIT_HOST, KIT_PORT (10300), LOCAL_HOST, LOCAL_PORT (10301).
"""
import asyncio, logging, os, time
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s")
KIT = (os.environ.get("KIT_HOST", "127.0.0.1"), int(os.environ.get("KIT_PORT", "10300")))  # the GPU box
LOCAL = (os.environ.get("LOCAL_HOST", "127.0.0.1"), int(os.environ.get("LOCAL_PORT", "10301")))
LISTEN_PORT = int(os.environ.get("LISTEN_PORT", "10300"))

async def pipe(r, w):
    try:
        while (b := await r.read(65536)):
            w.write(b); await w.drain()
    except Exception:
        pass
    finally:
        try: w.close()
        except Exception: pass

async def handle(cr, cw):
    ur = uw = None
    peer = cw.get_extra_info("peername")
    for host, port in (KIT, LOCAL):
        try:
            ur, uw = await asyncio.wait_for(asyncio.open_connection(host, port), 1.5)
            logging.info("conn from %s -> %s:%s", peer[0] if peer else "?", host, port)
            break
        except Exception:
            continue
    if uw is None:
        cw.close(); return
    await asyncio.gather(pipe(cr, uw), pipe(ur, cw))

async def main():
    srv = await asyncio.start_server(handle, "0.0.0.0", LISTEN_PORT)
    async with srv:
        await srv.serve_forever()

asyncio.run(main())
