const Charts = (() => {
  const COLORS = {
    bid: '#2563eb',
    ask: '#ea580c',
    bidFill: 'rgba(37, 99, 235, 0.15)',
    askFill: 'rgba(234, 88, 12, 0.15)',
    mid: '#58a6ff',
    volume: 'rgba(88, 166, 255, 0.3)',
    grid: '#2a2f3a',
    text: '#8b94a7',
    bg: '#0b0e11'
  };

  function createCanvas(container, id) {
    const canvas = document.createElement('canvas');
    canvas.id = id;
    container.appendChild(canvas);
    const ctx = canvas.getContext('2d');
    resizeCanvas(canvas);
    window.addEventListener('resize', () => resizeCanvas(canvas));
    return ctx;
  }

  function resizeCanvas(canvas) {
    const rect = canvas.parentElement.getBoundingClientRect();
    const dpr = window.devicePixelRatio || 1;
    canvas.width = rect.width * dpr;
    canvas.height = rect.height * dpr;
    canvas.style.width = rect.width + 'px';
    canvas.style.height = rect.height + 'px';
    const ctx = canvas.getContext('2d');
    ctx.scale(dpr, dpr);
  }

  function drawGrid(ctx, w, h, margins, xStep, yStep) {
    ctx.strokeStyle = COLORS.grid;
    ctx.lineWidth = 1;
    ctx.beginPath();
    for (let x = margins.left; x <= w - margins.right; x += xStep) {
      ctx.moveTo(x, margins.top);
      ctx.lineTo(x, h - margins.bottom);
    }
    for (let y = margins.top; y <= h - margins.bottom; y += yStep) {
      ctx.moveTo(margins.left, y);
      ctx.lineTo(w - margins.right, y);
    }
    ctx.stroke();
  }

  function priceChart(ctx, candles, w, h) {
    const margins = { top: 20, right: 50, bottom: 40, left: 50 };
    const plotW = w - margins.left - margins.right;
    const plotH = h - margins.top - margins.bottom - 60;
    const volH = 50;

    if (!candles || candles.length === 0) return;

    const prices = candles.flatMap(c => [c.open, c.high, c.low, c.close]);
    const maxPrice = Math.max(...prices);
    const minPrice = Math.min(...prices);
    const priceRange = maxPrice - minPrice || 1;
    const maxVol = Math.max(...candles.map(c => c.volume)) || 1;

    const xScale = plotW / (candles.length - 1 || 1);
    const yScale = plotH / priceRange;
    const volScale = volH / maxVol;

    ctx.clearRect(0, 0, w, h);

    drawGrid(ctx, w, h, margins, plotW / 8, plotH / 4);

    ctx.font = '10px monospace';
    ctx.fillStyle = COLORS.text;
    ctx.textAlign = 'right';
    ctx.textBaseline = 'middle';
    for (let i = 0; i <= 4; i++) {
      const price = maxPrice - (priceRange * i / 4);
      const y = margins.top + (priceRange * i / 4) * yScale;
      ctx.fillText(price.toFixed(0), margins.left - 6, y);
    }

    ctx.textAlign = 'center';
    ctx.textBaseline = 'top';
    for (let i = 0; i < candles.length; i += Math.max(1, Math.floor(candles.length / 6))) {
      const x = margins.left + i * xScale;
      const date = new Date(candles[i].ts);
      ctx.fillText(date.toLocaleTimeString(), x, h - margins.bottom + 4);
    }

    const candleWidth = Math.max(1, xScale * 0.7);

    candles.forEach((c, i) => {
      const x = margins.left + i * xScale;
      const openY = margins.top + (maxPrice - c.open) * yScale;
      const highY = margins.top + (maxPrice - c.high) * yScale;
      const lowY = margins.top + (maxPrice - c.low) * yScale;
      const closeY = margins.top + (maxPrice - c.close) * yScale;
      const isUp = c.close >= c.open;
      const color = isUp ? COLORS.bid : COLORS.ask;

      ctx.strokeStyle = color;
      ctx.lineWidth = 1;
      ctx.beginPath();
      ctx.moveTo(x, highY);
      ctx.lineTo(x, lowY);
      ctx.stroke();

      ctx.fillStyle = isUp ? color : 'transparent';
      ctx.strokeStyle = color;
      ctx.lineWidth = 1;
      const bodyTop = Math.min(openY, closeY);
      const bodyHeight = Math.max(1, Math.abs(openY - closeY));
      ctx.fillRect(x - candleWidth / 2, bodyTop, candleWidth, bodyHeight);
      ctx.strokeRect(x - candleWidth / 2, bodyTop, candleWidth, bodyHeight);
    });

    const volTop = margins.top + plotH + 10;
    candles.forEach((c, i) => {
      const x = margins.left + i * xScale;
      const volH = c.volume * volScale;
      const isUp = c.close >= c.open;
      ctx.fillStyle = isUp ? COLORS.bid : COLORS.ask;
      ctx.fillRect(x - candleWidth / 2, volTop + (volH - volH), candleWidth, volH);
    });

    ctx.fillStyle = COLORS.mid;
    ctx.font = '10px monospace';
    ctx.textAlign = 'left';
    ctx.fillText('VOL', margins.left, volTop - 2);
  }

  function depthChart(ctx, depthData, w, h) {
    const margins = { top: 20, right: 20, bottom: 30, left: 50 };
    const plotW = w - margins.left - margins.right;
    const plotH = h - margins.top - margins.bottom;

    if (!depthData || (!depthData.bids?.length && !depthData.asks?.length)) return;

    const allLevels = [
      ...(depthData.bids || []).map(d => ({ ...d, side: 'bid' })),
      ...(depthData.asks || []).map(d => ({ ...d, side: 'ask' }))
    ];
    const maxCum = Math.max(...allLevels.map(d => d.cumQty)) || 1;
    const prices = allLevels.map(d => d.price);
    const minPrice = Math.min(...prices);
    const maxPrice = Math.max(...prices);
    const priceRange = maxPrice - minPrice || 1;

    const xScale = plotW / maxCum;
    const yScale = plotH / priceRange;

    ctx.clearRect(0, 0, w, h);
    drawGrid(ctx, w, h, margins, plotW / 5, plotH / 6);

    ctx.font = '10px monospace';
    ctx.fillStyle = COLORS.text;
    ctx.textAlign = 'right';
    ctx.textBaseline = 'middle';
    for (let i = 0; i <= 5; i++) {
      const price = maxPrice - (priceRange * i / 5);
      const y = margins.top + (priceRange * i / 5) * yScale;
      ctx.fillText(price.toFixed(0), margins.left - 6, y);
    }

    const midPrice = (minPrice + maxPrice) / 2;
    const midY = margins.top + (maxPrice - midPrice) * yScale;
    ctx.strokeStyle = COLORS.mid;
    ctx.setLineDash([4, 4]);
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.moveTo(margins.left, midY);
    ctx.lineTo(w - margins.right, midY);
    ctx.stroke();
    ctx.setLineDash([]);

    const bids = depthData.bids || [];
    if (bids.length) {
      ctx.fillStyle = COLORS.bidFill;
      ctx.beginPath();
      ctx.moveTo(margins.left, margins.top + (maxPrice - bids[0].price) * yScale);
      bids.forEach((b, i) => {
        const x = margins.left + b.cumQty * xScale;
        const y = margins.top + (maxPrice - b.price) * yScale;
        if (i === 0) ctx.lineTo(x, y);
        else ctx.lineTo(x, y);
      });
      ctx.lineTo(margins.left, margins.top + (maxPrice - bids[bids.length - 1].price) * yScale);
      ctx.closePath();
      ctx.fill();

      ctx.strokeStyle = COLORS.bid;
      ctx.lineWidth = 2;
      ctx.beginPath();
      bids.forEach((b, i) => {
        const x = margins.left + b.cumQty * xScale;
        const y = margins.top + (maxPrice - b.price) * yScale;
        if (i === 0) ctx.moveTo(x, y);
        else ctx.lineTo(x, y);
      });
      ctx.stroke();
    }

    const asks = depthData.asks || [];
    if (asks.length) {
      ctx.fillStyle = COLORS.askFill;
      ctx.beginPath();
      ctx.moveTo(margins.left, margins.top + (maxPrice - asks[0].price) * yScale);
      asks.forEach((a, i) => {
        const x = margins.left + a.cumQty * xScale;
        const y = margins.top + (maxPrice - a.price) * yScale;
        if (i === 0) ctx.lineTo(x, y);
        else ctx.lineTo(x, y);
      });
      ctx.lineTo(margins.left, margins.top + (maxPrice - asks[asks.length - 1].price) * yScale);
      ctx.closePath();
      ctx.fill();

      ctx.strokeStyle = COLORS.ask;
      ctx.lineWidth = 2;
      ctx.beginPath();
      asks.forEach((a, i) => {
        const x = margins.left + a.cumQty * xScale;
        const y = margins.top + (maxPrice - a.price) * yScale;
        if (i === 0) ctx.moveTo(x, y);
        else ctx.lineTo(x, y);
      });
      ctx.stroke();
    }

    ctx.fillStyle = COLORS.bid;
    ctx.font = 'bold 10px monospace';
    ctx.textAlign = 'left';
    ctx.fillText('BIDS', margins.left + 5, margins.top + 14);
    ctx.fillStyle = COLORS.ask;
    ctx.fillText('ASKS', w - margins.right - 35, margins.top + 14);
  }

  function treapSVG(root, side, maxDepth, width, height) {
    const nodeRadius = 20;
    const levelGap = 50;
    const horizontalGap = 30;

    const nodes = [];
    function layout(node, depth, x, parentX) {
      if (!node || depth >= maxDepth) return;
      const y = 30 + depth * levelGap;
      nodes.push({ ...node, x, y, depth });
      const childOffset = horizontalGap * Math.pow(2, maxDepth - depth - 1);
      if (node.left) layout(node.left, depth + 1, x - childOffset, x);
      if (node.right) layout(node.right, depth + 1, x + childOffset, x);
    }

    layout(root, 0, width / 2, width / 2);

    let svg = `<svg viewBox="0 0 ${width} ${height}" xmlns="http://www.w3.org/2000/svg">`;

    const color = side === 'BID' ? COLORS.bid : COLORS.ask;

    nodes.forEach(n => {
      if (n.left) {
        const child = nodes.find(c => c.price === n.left.price && c.depth === n.depth + 1);
        if (child) {
          svg += `<line x1="${n.x}" y1="${n.y + nodeRadius}" x2="${child.x}" y2="${child.y - nodeRadius}" stroke="${color}" stroke-width="1.5" opacity="0.6"/>`;
        }
      }
      if (n.right) {
        const child = nodes.find(c => c.price === n.right.price && c.depth === n.depth + 1);
        if (child) {
          svg += `<line x1="${n.x}" y1="${n.y + nodeRadius}" x2="${child.x}" y2="${child.y - nodeRadius}" stroke="${color}" stroke-width="1.5" opacity="0.6"/>`;
        }
      }
    });

    nodes.forEach(n => {
      const qtyScale = Math.min(1.5, 0.5 + Math.log10(n.qty + 1) * 0.2);
      const r = Math.max(12, Math.min(30, nodeRadius * qtyScale));
      const flashClass = n.flash ? 'flash' : '';
      svg += `<g class="treap-node ${flashClass}" transform="translate(${n.x},${n.y})">
        <circle r="${r}" fill="${color}" fill-opacity="0.2" stroke="${color}" stroke-width="2"/>
        <text y="4" text-anchor="middle" font-size="11" font-family="monospace" fill="${COLORS.text}" font-weight="bold">${n.price}</text>
        <text y="18" text-anchor="middle" font-size="8" font-family="monospace" fill="${COLORS.muted}">${n.orders} ord</text>
      </g>`;
    });

    svg += '</svg>';
    return svg;
  }

  return {
    createCanvas,
    priceChart,
    depthChart,
    treapSVG,
    COLORS
  };
})();