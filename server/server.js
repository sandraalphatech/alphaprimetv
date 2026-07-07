const express  = require('express');
const bcrypt   = require('bcryptjs');
const jwt      = require('jsonwebtoken');
const cors     = require('cors');
const fs       = require('fs');
const path     = require('path');

const app    = express();
const PORT   = process.env.PORT || 3000;
const SECRET = process.env.JWT_SECRET || 'alphaprime-secret-2024';
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
  const existing = devices.find(d => d.mac === mac.toLowerCase());

  if (existing) {
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

// App verifica estado de ativação
app.post('/api/device/check', (req, res) => {
  const { mac } = req.body;
  if (!mac) return res.status(400).json({ error: 'mac obrigatório' });

  const device = getDevices().find(d => d.mac === mac.toLowerCase());
  if (!device) return res.json({ activated: false });

  if (device.expiresAt && new Date(device.expiresAt) < new Date()) {
    const devices = getDevices();
    const idx = devices.findIndex(d => d.mac === mac.toLowerCase());
    devices[idx].activated = false;
    saveDevices(devices);
    return res.json({ activated: false, reason: 'expired' });
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
  let device = devices.find(d => d.mac === mac.toLowerCase());

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

// ─── START ────────────────────────────────────────────────────────────────────
app.listen(PORT, () => {
  console.log(`\n🚀 Alpha Prime Server → http://localhost:${PORT}`);
  console.log(`📋 Painel Admin      → http://localhost:${PORT}/admin.html`);
  console.log(`📱 Página Ativar     → http://localhost:${PORT}/activate.html`);
  console.log(`🔑 Login: admin / admin123\n`);
});
