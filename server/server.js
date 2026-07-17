require('dotenv').config();

const express  = require('express');
const bcrypt   = require('bcryptjs');
const jwt      = require('jsonwebtoken');
const cors     = require('cors');
const fs       = require('fs');
const path     = require('path');
const https    = require('https');
const QRCode   = require('qrcode');

const app    = express();
const PORT   = process.env.PORT || 3000;
const SECRET = process.env.JWT_SECRET || 'alphaprime-secret-2024';

// ─── PAGARME CONFIG ───────────────────────────────────────────────────────────
const PAGARME_KEY = process.env.PAGARME_SECRET_KEY || '';
const PLAN_PRICES = {
  yearly:   { amount: 2500, days: 365,  label: 'Licença Anual Alpha Prime' },
  lifetime: { amount: 6500, days: null, label: 'Licença Vitalícia Alpha Prime' }
};
const DATA   = path.join(__dirname, 'data');

// ─── STORAGE (JSON files) ─────────────────────────────────────────────────────
if (!fs.existsSync(DATA)) fs.mkdirSync(DATA);

function readJson(file) {
  const p = path.join(DATA, file);
  if (!fs.existsSync(p)) return [];
  return JSON.parse(fs.readFileSync(p, 'utf8'));
}

function writeJson(file, data) {
  fs.writeFileSync(path.join(DATA, file), JSON.stringify(data, null, 2));
}

// Init admin padrão
const admins = readJson('admins.json');
if (!admins.find(a => a.username === 'admin')) {
  admins.push({ id: 1, username: 'admin', passwordHash: bcrypt.hashSync('admin123', 10) });
  writeJson('admins.json', admins);
  console.log('✅ Admin criado  →  user: admin  |  pass: admin123');
}

// ─── MIDDLEWARE ───────────────────────────────────────────────────────────────
app.use(cors());
app.use(express.json());
// Serve o dashboard existente
const DASHBOARD = path.join(__dirname, '..', 'Alpha', 'dashboard');
app.use(express.static(DASHBOARD));

// ─── AUTH ─────────────────────────────────────────────────────────────────────
function authRequired(req, res, next) {
  const token = (req.headers.authorization || '').replace('Bearer ', '');
  if (!token) return res.status(401).json({ error: 'Token em falta' });
  try { req.admin = jwt.verify(token, SECRET); next(); }
  catch { res.status(401).json({ error: 'Token inválido' }); }
}

// ─── HELPERS ─────────────────────────────────────────────────────────────────
function getDevices() { return readJson('devices.json'); }
function saveDevices(d) { writeJson('devices.json', d); }

// ─── APP API ──────────────────────────────────────────────────────────────────

// Registo automático quando app abre
app.post('/api/device/register', (req, res) => {
  const { mac, deviceKey } = req.body;
  if (!mac || !deviceKey) return res.status(400).json({ error: 'mac e deviceKey obrigatórios' });

  const devices  = getDevices();
  // deviceKey (ANDROID_ID) é o identificador único real — MAC pode ser idêntico
  // em dispositivos modernos que retornam 00:00:00:00:00:00 por restrições de privacidade.
  const existing = devices.find(d => d.deviceKey === deviceKey)
    || devices.find(d => d.mac === mac.toLowerCase());

  if (existing) {
    // Atualiza o MAC caso o dispositivo tenha gerado um novo (ex.: após correção do app)
    if (existing.deviceKey === deviceKey && existing.mac !== mac.toLowerCase()) {
      existing.mac = mac.toLowerCase();
      saveDevices(devices);
    }
    return res.json({
      registered: true,
      activated: existing.activated,
      plan: existing.plan,
      expiresAt: existing.expiresAt
    });
  }

  devices.push({
    id: Date.now(),
    mac: mac.toLowerCase(),
    deviceKey,
    activated: false,
    plan: 'basic',
    activatedAt: null,
    expiresAt: null,
    notes: '',
    createdAt: new Date().toISOString()
  });
  saveDevices(devices);
  res.json({ registered: true, activated: false });
});

// Alias para compatibilidade com o app (chama register-trial)
app.post('/api/device/register-trial', (req, res) => {
  const mac        = (req.body.mac_address || req.body.mac || '').toLowerCase();
  const deviceKey  = req.body.devicekey   || req.body.deviceKey || '';
  const modelo     = req.body.modelo_dispositivo || '';

  if (!mac || !deviceKey) return res.status(400).json({ success: false, error: 'mac e deviceKey obrigatórios' });

  const devices  = getDevices();
  const existing = devices.find(d => d.deviceKey === deviceKey)
    || devices.find(d => d.mac === mac);

  if (existing) {
    if (existing.deviceKey === deviceKey && existing.mac !== mac) {
      existing.mac = mac;
      saveDevices(devices);
    }
    const validade = existing.expiresAt || null;
    return res.json({ success: true, validade });
  }

  const trialEnd = new Date();
  trialEnd.setDate(trialEnd.getDate() + 7);

  devices.push({
    id: Date.now(),
    mac,
    deviceKey,
    modelo_dispositivo: modelo,
    activated: false,
    plan: 'trial',
    activatedAt: null,
    expiresAt: trialEnd.toISOString(),
    notes: '',
    createdAt: new Date().toISOString()
  });
  saveDevices(devices);
  res.json({ success: true, validade: trialEnd.toISOString() });
});

// App verifica estado de ativação
app.post('/api/device/check', (req, res) => {
  const { mac, deviceKey } = req.body;
  if (!mac) return res.status(400).json({ error: 'mac obrigatório' });

  const devices = getDevices();
  // Busca por deviceKey primeiro (identificador único real), depois por MAC como fallback
  const device = (deviceKey && devices.find(d => d.deviceKey === deviceKey))
    || devices.find(d => d.mac === mac.toLowerCase());
  if (!device) return res.json({ activated: false });

  if (device.expiresAt && new Date(device.expiresAt) < new Date()) {
    const idx = devices.findIndex(d => d.deviceKey === device.deviceKey || d.mac === device.mac);
    devices[idx].activated = false;
    saveDevices(devices);
    return res.json({ activated: false, reason: 'expired', plan: device.plan });
  }

  res.json({
    activated: device.activated,
    plan: device.plan,
    expiresAt: device.expiresAt,
    notes: device.notes
  });
});

// ─── DEVICE LOGIN (utilizador) ───────────────────────────────────────────────
app.post('/api/device/login', (req, res) => {
  const { mac, deviceKey } = req.body;
  if (!mac || !deviceKey) return res.status(400).json({ error: 'mac e deviceKey obrigatórios' });

  const devices = getDevices();
  let device = devices.find(d => d.deviceKey === deviceKey)
    || devices.find(d => d.mac === mac.toLowerCase());

  if (!device) {
    // Regista automaticamente
    device = { id: Date.now(), mac: mac.toLowerCase(), deviceKey, activated: false,
      plan: 'basic', activatedAt: null, expiresAt: null, notes: '', playlists: [],
      createdAt: new Date().toISOString() };
    devices.push(device);
    saveDevices(devices);
  }

  if (device.deviceKey !== deviceKey) return res.status(401).json({ error: 'Device Key inválida' });

  const expired = device.expiresAt && new Date(device.expiresAt) < new Date();
  const token = jwt.sign({ mac: device.mac }, SECRET, { expiresIn: '24h' });

  res.json({
    token,
    activated: device.activated && !expired,
    status: !device.activated ? 'pending' : expired ? 'expired' : 'active',
    plan: device.plan,
    expiresAt: device.expiresAt,
    mac: device.mac
  });
});

// Middleware de auth de dispositivo
function deviceAuth(req, res, next) {
  const token = (req.headers.authorization || '').replace('Bearer ', '');
  if (!token) return res.status(401).json({ error: 'Token em falta' });
  try { req.device = jwt.verify(token, SECRET); next(); }
  catch { res.status(401).json({ error: 'Token inválido' }); }
}

// ─── PLAYLISTS ────────────────────────────────────────────────────────────────
app.get('/api/device/playlists', deviceAuth, (req, res) => {
  const device = getDevices().find(d => d.mac === req.device.mac);
  res.json(device?.playlists || []);
});

app.post('/api/device/playlists', deviceAuth, (req, res) => {
  const { name, url, type = 'm3u', server, username, password } = req.body;
  if (!name) return res.status(400).json({ error: 'Nome obrigatório' });

  const devices = getDevices();
  const idx = devices.findIndex(d => d.mac === req.device.mac);
  if (idx === -1) return res.status(404).json({ error: 'Dispositivo não encontrado' });

  if (!devices[idx].playlists) devices[idx].playlists = [];
  const playlist = { id: Date.now(), name, url: url || '', type, server: server || '', username: username || '', password: password || '', createdAt: new Date().toISOString() };
  devices[idx].playlists.push(playlist);
  saveDevices(devices);
  res.json(playlist);
});

app.put('/api/device/playlists/:id', deviceAuth, (req, res) => {
  const devices = getDevices();
  const idx = devices.findIndex(d => d.mac === req.device.mac);
  if (idx === -1) return res.status(404).json({ error: 'Não encontrado' });

  const pIdx = (devices[idx].playlists || []).findIndex(p => p.id == req.params.id);
  if (pIdx === -1) return res.status(404).json({ error: 'Playlist não encontrada' });

  devices[idx].playlists[pIdx] = { ...devices[idx].playlists[pIdx], ...req.body };
  saveDevices(devices);
  res.json(devices[idx].playlists[pIdx]);
});

app.delete('/api/device/playlists/:id', deviceAuth, (req, res) => {
  const devices = getDevices();
  const idx = devices.findIndex(d => d.mac === req.device.mac);
  if (idx !== -1) {
    devices[idx].playlists = (devices[idx].playlists || []).filter(p => p.id != req.params.id);
    saveDevices(devices);
  }
  res.json({ success: true });
});

// ─── ADMIN API ────────────────────────────────────────────────────────────────

// Login
app.post('/api/admin/login', (req, res) => {
  const { username, password } = req.body;
  const admin = readJson('admins.json').find(a => a.username === username);
  if (!admin || !bcrypt.compareSync(password, admin.passwordHash)) {
    return res.status(401).json({ error: 'Credenciais inválidas' });
  }
  const token = jwt.sign({ id: admin.id, username: admin.username }, SECRET, { expiresIn: '8h' });
  res.json({ token, username: admin.username });
});

// Listar dispositivos
app.get('/api/admin/devices', authRequired, (req, res) => {
  const { search } = req.query;
  let devices = getDevices();
  if (search) {
    const q = search.toLowerCase();
    devices = devices.filter(d =>
      d.mac.includes(q) ||
      (d.deviceKey || '').toLowerCase().includes(q) ||
      (d.notes || '').toLowerCase().includes(q)
    );
  }
  res.json(devices.sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt)));
});

// Ativar dispositivo
app.post('/api/admin/activate', authRequired, (req, res) => {
  const { mac, plan = 'basic', days = 30, notes = '' } = req.body;
  if (!mac) return res.status(400).json({ error: 'mac obrigatório' });

  const devices = getDevices();
  const idx = devices.findIndex(d => d.mac === mac.toLowerCase());
  if (idx === -1) return res.status(404).json({ error: 'Dispositivo não encontrado' });

  const expiresAt = new Date();
  expiresAt.setDate(expiresAt.getDate() + Number(days));

  devices[idx] = { ...devices[idx], activated: true, plan, activatedAt: new Date().toISOString(), expiresAt: expiresAt.toISOString(), notes };
  saveDevices(devices);
  res.json({ success: true, expiresAt: expiresAt.toISOString() });
});

// Desativar dispositivo
app.post('/api/admin/deactivate', authRequired, (req, res) => {
  const devices = getDevices();
  const idx = devices.findIndex(d => d.mac === req.body.mac?.toLowerCase());
  if (idx !== -1) { devices[idx].activated = false; saveDevices(devices); }
  res.json({ success: true });
});

// Apagar dispositivo
app.delete('/api/admin/devices/:mac', authRequired, (req, res) => {
  const mac = decodeURIComponent(req.params.mac).toLowerCase();
  const devices = getDevices().filter(d => d.mac !== mac);
  saveDevices(devices);
  res.json({ success: true });
});

// Alterar senha
app.post('/api/admin/change-password', authRequired, (req, res) => {
  const { currentPassword, newPassword } = req.body;
  const admins = readJson('admins.json');
  const idx = admins.findIndex(a => a.id === req.admin.id);
  if (!bcrypt.compareSync(currentPassword, admins[idx].passwordHash)) {
    return res.status(400).json({ error: 'Senha atual incorreta' });
  }
  admins[idx].passwordHash = bcrypt.hashSync(newPassword, 10);
  writeJson('admins.json', admins);
  res.json({ success: true });
});

// Stats
app.get('/api/admin/stats', authRequired, (req, res) => {
  const devices = getDevices();
  const now = new Date();
  const in3days = new Date(); in3days.setDate(in3days.getDate() + 3);
  res.json({
    total: devices.length,
    active: devices.filter(d => d.activated).length,
    pending: devices.filter(d => !d.activated).length,
    expiringSoon: devices.filter(d => d.activated && d.expiresAt && new Date(d.expiresAt) < in3days && new Date(d.expiresAt) > now).length
  });
});

// ─── PAGARME HELPERS ─────────────────────────────────────────────────────────

function pagarmeRequest(method, urlPath, body) {
  return new Promise((resolve, reject) => {
    if (!PAGARME_KEY || PAGARME_KEY.startsWith('sk_test_COLOQUE')) {
      return reject(new Error('PAGARME_SECRET_KEY não configurada no .env'));
    }
    const auth = Buffer.from(PAGARME_KEY + ':').toString('base64');
    const data = body ? JSON.stringify(body) : null;
    const options = {
      hostname: 'api.pagar.me',
      path: '/core/v5' + urlPath,
      method,
      headers: {
        'Authorization': 'Basic ' + auth,
        'Content-Type': 'application/json',
        ...(data ? { 'Content-Length': Buffer.byteLength(data) } : {})
      }
    };
    const req = https.request(options, res => {
      let raw = '';
      res.on('data', c => (raw += c));
      res.on('end', () => {
        try { resolve({ status: res.statusCode, data: JSON.parse(raw) }); }
        catch { reject(new Error('PagarME parse error: ' + raw.slice(0, 200))); }
      });
    });
    req.on('error', reject);
    if (data) req.write(data);
    req.end();
  });
}

function getPending() { return readJson('pending_payments.json'); }
function savePending(p) { writeJson('pending_payments.json', p); }

async function activateLicenseFromPayment(entry) {
  const devices = getDevices();
  let idx = devices.findIndex(d => d.mac === entry.mac);

  if (idx === -1) {
    devices.push({
      id: Date.now(),
      mac: entry.mac,
      deviceKey: entry.deviceKey || '',
      activated: false,
      plan: entry.plan,
      activatedAt: null,
      expiresAt: null,
      notes: '',
      playlists: [],
      createdAt: new Date().toISOString()
    });
    idx = devices.length - 1;
  }

  const info = PLAN_PRICES[entry.plan] || PLAN_PRICES.yearly;
  let expiresAt = null;
  if (info.days) {
    const exp = new Date();
    exp.setDate(exp.getDate() + info.days);
    expiresAt = exp.toISOString();
  }

  devices[idx] = {
    ...devices[idx],
    activated: true,
    plan: entry.plan,
    activatedAt: new Date().toISOString(),
    expiresAt,
    nome_dispositivo: entry.deviceName || devices[idx].nome_dispositivo || '',
    deviceModel: entry.deviceModel || devices[idx].deviceModel || ''
  };
  saveDevices(devices);
  console.log(`✅ Licença ativada via Pix: ${entry.mac} (${entry.plan})`);
}

// ─── PAYMENTS — PIX ───────────────────────────────────────────────────────────

app.post('/api/payments/pix/create', async (req, res) => {
  const { mac, deviceKey, plan, deviceName, deviceModel } = req.body;

  if (!mac || !plan || !PLAN_PRICES[plan]) {
    return res.status(400).json({ error: 'mac e plan obrigatórios' });
  }

  const info = PLAN_PRICES[plan];

  try {
    const { status, data } = await pagarmeRequest('POST', '/orders', {
      code: 'AP-' + Date.now(),
      items: [{ amount: info.amount, description: info.label, quantity: 1 }],
      customer: {
        name: deviceName || 'Cliente Alpha Prime',
        email: 'pagamento@alphaprime.app',
        type: 'individual',
        document: '00000000191'
      },
      payments: [{ payment_method: 'pix', pix: { expires_in: 3600 } }]
    });

    if (status !== 201 || !data?.charges?.[0]) {
      console.error('PagarME create error:', JSON.stringify(data).slice(0, 300));
      return res.status(502).json({ error: 'Erro ao criar cobrança PagarME' });
    }

    const charge = data.charges[0];
    const pixCode = charge.last_transaction?.qr_code;
    const orderId = data.id;

    if (!pixCode) {
      console.error('PagarME: qr_code ausente', JSON.stringify(charge).slice(0, 300));
      return res.status(502).json({ error: 'QR Code não retornado pelo PagarME' });
    }

    const qrDataUri = await QRCode.toDataURL(pixCode, {
      width: 220, margin: 1,
      color: { dark: '#000000', light: '#ffffff' }
    });

    const pending = getPending();
    pending.push({
      paymentId: orderId,
      mac: mac.toLowerCase(),
      deviceKey: deviceKey || '',
      plan,
      deviceName: deviceName || '',
      deviceModel: deviceModel || '',
      status: 'pending',
      createdAt: new Date().toISOString()
    });
    savePending(pending);

    res.json({ paymentId: orderId, qrCodeBase64: qrDataUri, qrCodeText: pixCode });
  } catch (e) {
    console.error('pix/create exception:', e.message);
    res.status(500).json({ error: e.message || 'Erro interno ao gerar Pix' });
  }
});

app.get('/api/payments/pix/status', async (req, res) => {
  const { id } = req.query;
  if (!id) return res.status(400).json({ error: 'id obrigatório' });

  const pending = getPending();
  const entry = pending.find(p => p.paymentId === id);
  if (!entry) return res.json({ status: 'unknown' });
  if (entry.status === 'paid') return res.json({ status: 'paid' });

  try {
    const { data } = await pagarmeRequest('GET', '/orders/' + id, null);
    const orderStatus = data.status;

    if (orderStatus === 'paid') {
      await activateLicenseFromPayment(entry);
      const all = getPending();
      const idx = all.findIndex(p => p.paymentId === id);
      if (idx !== -1) { all[idx].status = 'paid'; savePending(all); }
      return res.json({ status: 'paid' });
    }
    if (orderStatus === 'canceled' || orderStatus === 'failed') {
      return res.json({ status: 'failed' });
    }
    return res.json({ status: 'pending' });
  } catch (e) {
    console.error('pix/status exception:', e.message);
    res.json({ status: 'pending' });
  }
});

// Webhook PagarME — configure a URL pública deste endpoint no painel PagarME
app.post('/api/payments/webhook', express.json(), async (req, res) => {
  res.json({ received: true }); // Responde imediatamente para o PagarME não retentar

  const event = req.body;
  if (event?.type !== 'order.paid') return;

  const orderId = event.data?.id;
  if (!orderId) return;

  const pending = getPending();
  const entry = pending.find(p => p.paymentId === orderId && p.status !== 'paid');
  if (!entry) return;

  try {
    await activateLicenseFromPayment(entry);
    const all = getPending();
    const idx = all.findIndex(p => p.paymentId === orderId);
    if (idx !== -1) { all[idx].status = 'paid'; savePending(all); }
  } catch (e) {
    console.error('webhook activate error:', e.message);
  }
});

// ─── START ────────────────────────────────────────────────────────────────────
app.listen(PORT, () => {
  const keyOk = PAGARME_KEY && !PAGARME_KEY.startsWith('sk_test_COLOQUE');
  console.log(`\n🚀 Alpha Prime Server → http://localhost:${PORT}`);
  console.log(`📋 Painel Admin      → http://localhost:${PORT}/admin.html`);
  console.log(`📱 Página Ativar     → http://localhost:${PORT}/activate.html`);
  console.log(`💳 Webhook PagarME   → POST /api/payments/webhook`);
  console.log(`🔑 PagarME Key       → ${keyOk ? '✅ configurada' : '⚠️  NÃO configurada — edite .env'}`);
  console.log(`🔑 Login admin: admin / admin123\n`);
});
