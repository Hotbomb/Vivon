// The touchpad's sender: finger moves are added up and sent one request at a time, so a slow
// answer never queues a backlog. Only whole pixels are sent; the fractions left over wait for the
// next move. (Sending when only fractions were left re-sent "move 0" forever and froze the page,
// because the DeX pointer answers at once.)
export function makePadSender(onPointer) {
  let acc = { dx: 0, dy: 0, scroll: 0, click: false };
  let busy = false;
  const flush = () => {
    if (busy) return;
    const a = acc;
    const d = { dx: Math.round(a.dx), dy: Math.round(a.dy), scroll: Math.round(a.scroll), click: a.click };
    if (!d.dx && !d.dy && !d.scroll && !d.click) return;
    acc = { dx: a.dx - d.dx, dy: a.dy - d.dy, scroll: a.scroll - d.scroll, click: false };
    busy = true;
    Promise.resolve().then(() => onPointer(d)).catch(() => {}).finally(() => { busy = false; flush(); });
  };
  return (k, v) => { acc[k] = k === 'click' ? true : acc[k] + v; flush(); };
}
