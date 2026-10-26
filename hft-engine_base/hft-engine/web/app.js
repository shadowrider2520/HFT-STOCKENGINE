const App = (() => {
  const API = (new URLSearchParams(location.search).get('api') || '').replace(/\/+$/, '');
  const url = path => API + path;

  let lastEventId = -1;
  let eventPollInterval;
  let eventPaused = false;
  let activeEventTypes = new Set();
  let currentTicker = 'BTCUSDT';
  let traderName = '';
  let priceChartCtx, depthChartCtx;
  let lastPrice = null;

  function $(id) { return document.getElementById(id); }

  function init() {
    loadTraderName();
    const initialTicker = ($('ticker')?.value || '').trim().toUpperCase();
    if (initialTicker) currentTicker = initialTicker;
    $('bookTicker').textContent = currentTicker;
    setupEventListeners();
    setupCharts();
    startPolling();
    fetchInitialData();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }

  function loadTraderName() {
    try {
      const saved = localStorage.getItem('hft_trader_name');
      if (saved) {
        traderName = saved;
        $('trader').value = traderName;
      }
    } catch (e) {}
  }

  function saveTraderName(name) {
    try {
      localStorage.setItem('hft_trader_name', name);
      traderName = name;
    } catch (e) {}
  }

  function setupEventListeners() {
    const trader = $('trader');
    const ticker = $('ticker');
    const submitBtn = $('submitBtn');
    const simBtn = $('simBtn');
    const pauseBtn = $('pauseEvents');
    const clearBtn = $('clearEvents');
    const filterChips = document.querySelectorAll('.filter-chip');

    if (trader) trader.addEventListener('change', e => saveTraderName(e.target.value.trim().toLowerCase()));
    if (ticker) ticker.addEventListener('change', e => {
      const next = e.target.value.trim().toUpperCase();
      if (!next) {
        // An empty ticker used to request /api/book/ and blank every panel.
        e.target.value = currentTicker;
        return;
      }
      currentTicker = next;
      $('bookTicker').textContent = currentTicker;
      refreshBook();
      refreshTrades();
      refreshPrice();
      refreshTickerStats();
      refreshDepth();
      refreshTreap();
      refreshCandles();
    });

    if (submitBtn) submitBtn.addEventListener('click', submitOrder);
    if (simBtn) simBtn.addEventListener('click', runSimulation);
    if (pauseBtn) pauseBtn.addEventListener('click', toggleEventPause);
    // The local binding was called clearEvents and shadowed the handler of the
    // same name, so the listener registered the button element itself (a no-op).
    if (clearBtn) clearBtn.addEventListener('click', clearEvents);
    if (filterChips) filterChips.forEach(chip => {
      if (chip.classList.contains('active')) activeEventTypes.add(chip.dataset.type);
      chip.addEventListener('click', () => {
        chip.classList.toggle('active');
        if (chip.classList.contains('active')) activeEventTypes.add(chip.dataset.type);
        else activeEventTypes.delete(chip.dataset.type);
        filterEvents();
      });
    });
  }

  function setupCharts() {
    priceChartCtx = Charts.createCanvas($('priceChart'), 'priceChartCanvas');
    depthChartCtx = Charts.createCanvas($('depthChart'), 'depthChartCanvas');
  }

  function startPolling() {
    refreshBook();
    refreshTrades();
    refreshPrice();
    refreshStats();
    refreshTickerStats();
    refreshDepth();
    refreshTreap();
    refreshCandles();
    refreshTraders();

    setInterval(() => {
      refreshBook();
      refreshTrades();
      refreshPrice();
      refreshStats();
      refreshTickerStats();
      refreshDepth();
      refreshTreap();
      refreshCandles();
      refreshTraders();
    }, 1000);

    pollEvents();
    eventPollInterval = setInterval(pollEvents, 500);
  }

  async function fetchInitialData() {
    await Promise.all([
      refreshBook(),
      refreshTrades(),
      refreshPrice(),
      refreshStats(),
      refreshTickerStats(),
      refreshDepth(),
      refreshTreap(),
      refreshCandles(),
      refreshTraders()
    ]);
  }

  function refreshBook() {
    return fetch(url('/api/book/' + encodeURIComponent(currentTicker)))
      .then(r => r.json())
      .then(data => {
        renderLevels('bids', data.bids, 'bid');
        renderLevels('asks', data.asks, 'ask');
        updateSpread(data.bids, data.asks);
      })
      .catch(() => {});
  }

  function renderLevels(tbodyId, levels, side) {
    const tbody = $(tbodyId);
    if (!levels || levels.length === 0) {
      tbody.innerHTML = '<tr><td class="empty" colspan="3">no resting orders</td></tr>';
      return;
    }
    let html = '';
    for (let i = 0; i < levels.length; i++) {
      const l = levels[i];
      const barWidth = Math.min(100, (l.qty / (levels[0]?.qty || 1)) * 100);
      html += `<tr class="${side}">
        <td class="price">${l.price}</td>
        <td>
          <div style="display:flex;align-items:center;gap:6px">
            <span>${l.qty.toLocaleString()}</span>
            <div style="flex:1;height:4px;background:var(--line);border-radius:2px;overflow:hidden">
              <div style="width:${barWidth}%;height:100%;background:${side==='bid'?'var(--bid)':'var(--ask)'}"></div>
            </div>
          </div>
        </td>
        <td>${l.orders}</td>
      </tr>`;
    }
    tbody.innerHTML = html;
  }

  function updateSpread(bids, asks) {
    const bestBid = bids[0]?.price || '-';
    const bestAsk = asks[0]?.price || '-';
    const spread = (bids[0] && asks[0]) ? asks[0].price - bids[0].price : '-';
    $('bestBid').textContent = bestBid;
    $('bestAsk').textContent = bestAsk;
    $('spread').textContent = spread;
  }

  function refreshTrades() {
    return fetch(url('/api/trades?n=50'))
      .then(r => r.json())
      .then(trades => {
        const tbody = $('trades');
        if (!trades || trades.length === 0) {
          tbody.innerHTML = '<tr><td class="empty" colspan="6">no trades yet</td></tr>';
          return;
        }
        let html = '';
        for (let i = 0; i < trades.length; i++) {
          const t = trades[i];
          const time = new Date(t.timestamp).toLocaleTimeString();
          const aggressor = t.buyerId === t.sellerId ? '' : (i === 0 ? 'aggressor' : '');
          html += `<tr>
            <td>${time}</td>
            <td>${t.price}</td>
            <td>${t.quantity}</td>
            <td class="bid">${t.buyerId}</td>
            <td class="ask">${t.sellerId}</td>
            <td>${t.ticker}</td>
          </tr>`;
        }
        tbody.innerHTML = html;
      })
      .catch(() => {});
  }

  function refreshPrice() {
    return fetch(url('/api/price/' + encodeURIComponent(currentTicker)))
      .then(r => r.json())
      .then(data => {
        const priceEl = $('price');
        const sourceEl = $('priceSource');
        const badgeEl = $('feedBadge');

        if (lastPrice !== null && data.price !== lastPrice) {
          priceEl.classList.remove('up', 'down');
          priceEl.classList.add(data.price > lastPrice ? 'up' : 'down');
          setTimeout(() => priceEl.classList.remove('up', 'down'), 500);
        }
        lastPrice = data.price;

        priceEl.textContent = data.price;
        sourceEl.textContent = '(' + data.source + ')';

        if (badgeEl) {
          badgeEl.textContent = data.source === 'live' ? 'LIVE' : 'SIMULATED';
          badgeEl.className = 'feed-badge ' + (data.source === 'live' ? 'live' : 'sim');
        }
      })
      .catch(() => {});
  }

  function refreshStats() {
    return fetch(url('/api/stats'))
      .then(r => r.json())
      .then(s => {
        $('cAccepted').textContent = s.accepted.toLocaleString();
        $('cRejected').textContent = s.rejected.toLocaleString();
        $('cTrades').textContent = s.trades.toLocaleString();
      })
      .catch(() => {});
  }

  function refreshTickerStats() {
    return fetch(url('/api/stats/' + encodeURIComponent(currentTicker)))
      .then(r => r.json())
      .then(s => {
        $('lastPrice').textContent = s.lastPrice > 0 ? Number(s.lastPrice).toLocaleString() : '-';
        $('volume').textContent = Number(s.volume || 0).toLocaleString();
        $('tradeCount').textContent = Number(s.trades || 0).toLocaleString();
        $('bidHeight').textContent = s.bidTreapHeight;
        $('askHeight').textContent = s.askTreapHeight;
      })
      .catch(() => {});
  }

  function refreshDepth() {
    return fetch(url('/api/depth/' + encodeURIComponent(currentTicker)))
      .then(r => r.json())
      .then(data => {
        if (depthChartCtx) Charts.depthChart(depthChartCtx, data, depthChartCtx.canvas.width / (window.devicePixelRatio||1), depthChartCtx.canvas.height / (window.devicePixelRatio||1));
      })
      .catch(() => {});
  }

  function safeJson(ticker, side) {
    return fetch(url('/api/treap/' + encodeURIComponent(ticker) + '/' + side + '?depth=5'))
      .then(r => (r.ok ? r.json() : null))
      .catch(() => null);
  }

  function refreshTreap() {
    const w = Math.max(300, Math.floor($('bidTreap').clientWidth || 400));
    const h = 320;
    // Each side is fetched independently: one malformed/failed response used to
    // reject the shared Promise.all and blank both trees.
    return Promise.all([
      safeJson(currentTicker, 'bids'),
      safeJson(currentTicker, 'asks')
    ]).then(([bids, asks]) => {
      $('bidTreap').innerHTML = Charts.treapSVG(bids, 'BID', 5, w, h);
      $('askTreap').innerHTML = Charts.treapSVG(asks, 'ASK', 5, w, h);
    });
  }

  function refreshCandles() {
    return fetch(url('/api/candles/' + encodeURIComponent(currentTicker) + '?bucket=5'))
      .then(r => r.json())
      .then(candles => {
        if (priceChartCtx && candles) {
          const w = priceChartCtx.canvas.width / (window.devicePixelRatio||1);
          const h = priceChartCtx.canvas.height / (window.devicePixelRatio||1);
          Charts.priceChart(priceChartCtx, candles, w, h);
        }
      })
      .catch(() => {});
  }

  function refreshTraders() {
    return fetch(url('/api/traders'))
      .then(r => r.json())
      .then(traders => {
        const container = $('leaderboard');
        if (!traders || traders.length === 0) {
          container.innerHTML = '<div class="empty">no traders yet</div>';
          return;
        }
        let html = '';
        const maxPos = Math.max(...traders.map(x => Math.abs(x.netPosition))) || 1;
        traders.slice(0, 20).forEach((t, i) => {
          const net = t.netPosition;
          const isBuyer = net >= 0;
          const buyWidth = isBuyer ? (t.boughtQty / maxPos) * 100 : 0;
          const sellWidth = !isBuyer ? (t.soldQty / maxPos) * 100 : 0;
          html += `<div class="leaderboard-row ${isBuyer ? 'buyer' : 'seller'}">
            <span class="lb-rank">#${i+1}</span>
            <span class="lb-name">${t.traderId}</span>
            <div class="lb-bars">
              <div class="lb-bar buy" style="width:${buyWidth}%"></div>
              <div class="lb-bar sell" style="width:${sellWidth}%"></div>
            </div>
            <span class="lb-stats">${t.cashFlow >= 0 ? '+' : ''}${t.cashFlow.toLocaleString()} | ${t.trades} trades</span>
          </div>`;
        });
        container.innerHTML = html;
      })
      .catch(() => {});
  }

  function pollEvents() {
    if (eventPaused) return;
    fetch(url('/api/events?since=' + lastEventId + '&ticker=' + encodeURIComponent(currentTicker) + '&max=200'))
      .then(r => r.json())
      .then(events => {
        if (events && events.length > 0) {
          events.forEach(e => addEventRow(e));
          lastEventId = Math.max(lastEventId, ...events.map(e => e.id));
        }
      })
      .catch(() => {});
  }

  function addEventRow(e) {
    const container = $('eventTimeline');
    const row = document.createElement('div');
    row.className = 'event-row';
    const time = new Date(e.ts).toLocaleTimeString();
    const type = e.type || '';
    row.innerHTML = `
      <span>${e.id}</span>
      <span>${time}</span>
      <span>${e.ticker || '-'}</span>
      <span class="type type-${type}">${type.replace(/_/g, ' ')}</span>
      <span>${e.price || '-'}</span>
      <span>${e.traderId || '-'}</span>
      <span>${escapeHtml(e.detail || '')}</span>
    `;
    // New rows must respect the chips too; previously they were always shown.
    if (activeEventTypes.size > 0 && !activeEventTypes.has(type)) row.style.display = 'none';
    container.insertBefore(row, container.firstChild);
    while (container.children.length > 200) container.removeChild(container.lastChild);
  }

  function filterEvents() {
    document.querySelectorAll('.event-row').forEach(row => {
      const type = row.querySelector('.type').textContent.replace(/ /g, '_');
      row.style.display = activeEventTypes.size === 0 || activeEventTypes.has(type) ? '' : 'none';
    });
  }

  function toggleEventPause() {
    eventPaused = !eventPaused;
    const btn = $('pauseEvents');
    if (btn) {
      btn.textContent = eventPaused ? 'Resume' : 'Pause';
      btn.classList.toggle('secondary', !eventPaused);
    }
  }

  function clearEvents() {
    // Keep lastEventId so the cleared view does not refill with the same
    // history on the next 500 ms poll; new events still stream in.
    $('eventTimeline').innerHTML = '';
  }

  function submitOrder() {
    const raw = [
      $('trader').value.trim(),
      $('ticker').value.trim().toUpperCase(),
      $('side').value,
      $('price-in').value.trim(),
      $('qty').value.trim()
    ].join(',');

    const msg = $('orderMsg');
    msg.className = 'msg';
    msg.textContent = 'sending...';

    fetch(url('/api/order'), { method: 'POST', body: raw })
      .then(r => r.json().then(b => ({ status: r.status, body: b })))
      .then(res => {
        if (res.status !== 200) {
          msg.className = 'msg err';
          msg.textContent = 'rejected: ' + (res.body.error || 'bad request');
          return;
        }
        if (!res.body.accepted) {
          msg.className = 'msg err';
          msg.textContent = 'rejected: price level is full';
          return;
        }
        const n = res.body.trades.length;
        msg.className = 'msg ok';
        msg.textContent = n === 0 ? 'accepted, resting in the book' : 'accepted, ' + n + ' trade' + (n === 1 ? '' : 's') + ' executed';
        refreshBook();
        refreshTrades();
        refreshStats();
        refreshTickerStats();
        refreshDepth();
        refreshTreap();
      })
      .catch(e => {
        msg.className = 'msg err';
        msg.textContent = 'network error: ' + e.message;
      });
  }

  function runSimulation() {
    const n = $('simN').value;
    const mode = $('simMode').value;
    const box = $('simStats');
    const btn = $('simBtn');
    const pauseBtn = $('pauseEvents');

    if (btn) btn.disabled = true;
    if (box) box.innerHTML = '<div>running ' + Number(n).toLocaleString() + ' orders...</div>';
    eventPaused = true;
    if (pauseBtn) {
      pauseBtn.textContent = 'Paused (benchmark)';
      pauseBtn.classList.add('secondary');
    }

    fetch(url('/api/simulate?n=' + encodeURIComponent(n) + '&mode=' + mode + '&ticker=' + encodeURIComponent(currentTicker)), { method: 'POST' })
      .then(r => r.json())
      .then(s => {
        box.innerHTML =
          row('orders', s.orders.toLocaleString()) +
          row('mode', s.mode) +
          row('time', s.totalTimeMs.toLocaleString() + ' ms') +
          row('throughput', s.ordersPerSecond.toLocaleString() + ' orders/s') +
          row('trades', s.trades.toLocaleString()) +
          row('rejected', s.rejected.toLocaleString()) +
          row('bid treap height', s.bidHeight + ' (' + s.bidLevels.toLocaleString() + ' levels)') +
          row('ask treap height', s.askHeight + ' (' + s.askLevels.toLocaleString() + ' levels)');
        refreshBook();
        refreshTrades();
        refreshStats();
        refreshTickerStats();
        refreshDepth();
        refreshTreap();
        refreshCandles();
        refreshTraders();
      })
      .catch(e => { box.innerHTML = '<div class="msg err">simulation failed: ' + e.message + '</div>'; })
      .finally(() => {
        if (btn) btn.disabled = false;
        eventPaused = false;
        if (pauseBtn) {
          pauseBtn.textContent = 'Pause';
          pauseBtn.classList.remove('secondary');
        }
        pollEvents();
      });
  }

  function row(label, value) {
    return '<div><span>' + label + '</span><b>' + value + '</b></div>';
  }

  function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
  }

  return { init };
})();
