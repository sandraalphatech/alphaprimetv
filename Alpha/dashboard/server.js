require('dotenv').config();
const express = require('express');
const cors = require('cors');
const axios = require('axios');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const { createClient } = require('@supabase/supabase-js');
const bcrypt = require('bcryptjs');
const { Resend } = require('resend');

const resend = new Resend(process.env.RESEND_API_KEY);
const APP_BASE_URL = (process.env.APP_BASE_URL || 'http://localhost:3000').replace(/\/$/, '');

// token -> { email, expiresAt }
const resetTokens = new Map();

// ── Supabase ──────────────────────────────────────────────────────────────────
const supabase = createClient(
  process.env.SUPABASE_URL,
  process.env.SUPABASE_SERVICE_ROLE_KEY
);
const sessions = new Map(); // token -> { userId, email, nome }

const PAGARME_API = 'https://api.pagar.me/core/v5';
const authHeader = 'Basic ' + Buffer.from(`${process.env.PAGARME_SECRET_KEY}:`).toString('base64');
const MOCK_PAYMENTS = process.env.PAYMENT_PROVIDER === 'mock';
const MOCK_AUTO_CONFIRM_MS = 8000;

const PLANS = {
  yearly:   { amount: 2500, description: 'Licença Anual Alpha Prime',    days: 365  },
  lifetime: { amount: 6500, description: 'Licença Vitalícia Alpha Prime', days: null }
};

const devices = new Map(); // mac -> { active, plan, expiresAt, deviceKey }
const payments = new Map(); // orderId -> { mac, deviceKey, plan, status }
const playlists = new Map(); // mac -> [{ id, name, type, url, server, username, password, epgUrl, protected, pin }]
const activePlaylist = new Map(); // mac -> playlistId
const parental = new Map(); // mac -> { pinHash, lockedCategories: [] }

function hashPin(pin) {
  return crypto.createHash('sha256').update(pin, 'utf8').digest('hex');
}

// ── Persistência simples em arquivo ──────────────────────────────────────────
// Guarda o estado (devices/playlists/activePlaylist/parental) num .json local,
// para sobreviver a reinícios do servidor durante o desenvolvimento. Quando o
// projeto migrar para um banco de dados de verdade, isto pode ser substituído.
const DB_FILE = path.join(__dirname, 'data.json');

function loadState() {
  if (!fs.existsSync(DB_FILE)) return;
  try {
    const raw = JSON.parse(fs.readFileSync(DB_FILE, 'utf8'));
    (raw.devices || []).forEach(([k, v]) => devices.set(k, v));
    (raw.payments || []).forEach(([k, v]) => payments.set(k, v));
    (raw.playlists || []).forEach(([k, v]) => playlists.set(k, v));
    (raw.activePlaylist || []).forEach(([k, v]) => activePlaylist.set(k, v));
    (raw.parental || []).forEach(([k, v]) => parental.set(k, v));
    // Restaurar sessões (expiram após 30 dias)
    const cutoff = Date.now() - 30 * 24 * 3600 * 1000;
    (raw.sessions || []).forEach(([k, v]) => {
      if (!v.createdAt || v.createdAt > cutoff) sessions.set(k, v);
    });
  } catch (err) {
    console.error('Não foi possível carregar data.json:', err.message);
  }
}

let saveTimer = null;
function saveState() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => {
    const snapshot = {
      devices: [...devices.entries()],
      payments: [...payments.entries()],
      playlists: [...playlists.entries()],
      activePlaylist: [...activePlaylist.entries()],
      parental: [...parental.entries()],
      sessions: [...sessions.entries()]
    };
    fs.writeFile(DB_FILE, JSON.stringify(snapshot, null, 2), err => {
      if (err) console.error('Não foi possível salvar data.json:', err.message);
    });
  }, 150);
}

loadState();


const app = express();
app.use(cors());
app.use(express.json());
app.use(express.static(__dirname));

app.post('/api/payments/pix/create', async (req, res) => {
  const { mac, deviceKey, plan, deviceName, deviceModel, userToken } = req.body;
  if (!mac || !PLANS[plan]) {
    return res.status(400).json({ error: 'mac e plan são obrigatórios' });
  }

  const planInfo = PLANS[plan];

  if (MOCK_PAYMENTS) {
    const paymentId = 'mock_' + Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
    payments.set(paymentId, { paymentId, mac, deviceKey: deviceKey || '', plan, status: 'pending', deviceName: deviceName || '', deviceModel: deviceModel || '', userToken: userToken || null, tipoPagamento: 'pix' });

    setTimeout(() => {
      const payment = payments.get(paymentId);
      if (payment && payment.status === 'pending') {
        payment.status = 'paid';
        activateDevice(payment);
      }
    }, MOCK_AUTO_CONFIRM_MS);

    const fakeCopiaCola = `00020126580014BR.GOV.BCB.PIX0136mock-${paymentId}5204000053039865406${(planInfo.amount / 100).toFixed(2)}5802BR5919Alpha Prime Mock6009SAO PAULO62070503***6304MOCK`;
    return res.json({
      paymentId,
      qrCodeBase64: `https://api.qrserver.com/v1/create-qr-code/?size=220x220&data=${encodeURIComponent(fakeCopiaCola)}`,
      qrCodeText: fakeCopiaCola
    });
  }

  try {
    const order = await axios.post(`${PAGARME_API}/orders`, {
      items: [{
        amount: planInfo.amount,
        description: planInfo.description,
        quantity: 1,
        code: plan
      }],
      customer: {
        name: 'Cliente Alpha Prime',
        email: `${mac.replace(/[^a-z0-9]/gi, '')}@alphaprime.tv`,
        type: 'individual',
        document: '11144477735',
        document_type: 'CPF',
        phones: {
          mobile_phone: { country_code: '55', area_code: '11', number: '999999999' }
        }
      },
      payments: [{
        payment_method: 'pix',
        pix: { expires_in: 3600 }
      }],
      metadata: { mac, deviceKey: deviceKey || '', plan }
    }, { headers: { Authorization: authHeader } });

    const charge = order.data.charges?.[0];
    const tx = charge?.last_transaction;

    payments.set(order.data.id, { paymentId: order.data.id, mac, deviceKey: deviceKey || '', plan, status: 'pending', deviceName: deviceName || '', deviceModel: deviceModel || '', userToken: userToken || null, tipoPagamento: 'pix' });

    res.json({
      paymentId: order.data.id,
      qrCodeBase64: tx?.qr_code_url,
      qrCodeText: tx?.qr_code
    });
  } catch (err) {
    console.error('Erro ao criar cobrança Pix:', err.response?.data || err.message);
    res.status(502).json({ error: 'Não foi possível gerar a cobrança Pix' });
  }
});

app.post('/api/payments/card/create', (req, res) => {
  const { mac, deviceKey, plan, deviceName, deviceModel, userToken } = req.body;
  if (!mac || !PLANS[plan]) {
    return res.status(400).json({ error: 'mac e plan são obrigatórios' });
  }

  if (!MOCK_PAYMENTS) {
    return res.status(501).json({ error: 'Pagamento com cartão real ainda não configurado. Use PAYMENT_PROVIDER=mock para testes.' });
  }

  const paymentId = 'mock_card_' + Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
  const payment = { paymentId, mac, deviceKey: deviceKey || '', plan, status: 'paid', deviceName: deviceName || '', deviceModel: deviceModel || '', userToken: userToken || null, tipoPagamento: 'cartao' };
  payments.set(paymentId, payment);
  activateDevice(payment);

  res.json({ paymentId, status: 'paid' });
});

app.get('/api/payments/pix/status', (req, res) => {
  const payment = payments.get(req.query.id);
  if (!payment) return res.status(404).json({ error: 'Pagamento não encontrado' });
  res.json({ status: payment.status });
});

app.post('/api/payments/pix/simulate/:id', (req, res) => {
  if (!MOCK_PAYMENTS) return res.status(403).json({ error: 'Disponível apenas com PAYMENT_PROVIDER=mock' });

  const payment = payments.get(req.params.id);
  if (!payment) return res.status(404).json({ error: 'Pagamento não encontrado' });

  payment.status = 'paid';
  activateDevice(payment);
  res.json({ status: payment.status });
});

app.post('/api/payments/pix/webhook', (req, res) => {
  const event = req.body;
  const orderId = event.data?.id || event.data?.order?.id;
  const payment = orderId && payments.get(orderId);

  if (payment && (event.type === 'order.paid' || event.type === 'charge.paid')) {
    payment.status = 'paid';
    activateDevice(payment);
  } else if (payment && (event.type === 'order.payment_failed' || event.type === 'charge.payment_failed')) {
    payment.status = 'failed';
  }

  res.sendStatus(200);
});

function activateDevice(payment) {
  const { mac, deviceKey, plan, deviceName, deviceModel, userToken, tipoPagamento } = payment;
  const planInfo = PLANS[plan];
  const expiresAt = planInfo.days ? new Date(Date.now() + planInfo.days * 86400000).toISOString() : null;
  devices.set(mac, { active: true, plan, expiresAt, deviceKey });
  saveState();
  saveAtivacao({ mac, deviceKey, plan, deviceName, deviceModel, userToken, expiresAt, paymentId: payment.paymentId || null, tipoPagamento: tipoPagamento || null }).catch(e => console.error('Erro ao gravar ativação:', e));
}

const PLANO_MAP = { monthly: 'mensal', quarterly: 'trimestral', semiannual: 'semestral', yearly: 'anual', lifetime: 'vitalicio' };

const MAC_OUI_SERVER = {
  'FC65DE':'Amazon Fire TV','747548':'Amazon Fire TV','A002DC':'Amazon Fire TV',
  '84D6D0':'Amazon Fire TV','34D270':'Amazon Fire TV','40B4CD':'Amazon Fire TV',
  '680571':'Amazon Fire TV','AC63BE':'Amazon Fire TV','CC9E00':'Amazon Fire TV',
  'F0272D':'Amazon Fire TV','F0D2F1':'Amazon Fire TV','74C246':'Amazon Fire TV',
  '001247':'Samsung Smart TV','001377':'Samsung Smart TV','001599':'Samsung Smart TV',
  '001632':'Samsung Smart TV','84C9B2':'Samsung Smart TV','5C49EB':'Samsung Smart TV',
  'A8F274':'Samsung Smart TV','78BD06':'Samsung Smart TV','8C771F':'Samsung Smart TV',
  '001E75':'LG Smart TV','001F6B':'LG Smart TV','58A2B5':'LG Smart TV',
  '60E3AC':'LG Smart TV','A823FE':'LG Smart TV','8C3AE3':'LG Smart TV',
  'C4360C':'LG Smart TV','A0B4A5':'LG Smart TV',
  '001A79':'MAG Box','002583':'Formuler','B43A28':'Formuler',
  '207840':'Apple TV','6C709F':'Apple TV','9C207B':'Apple TV','7C6D62':'Apple TV',
  '286C07':'Xiaomi Mi Box','64B473':'Xiaomi Mi Box','F48B32':'Xiaomi Mi Box',
  '50642B':'Xiaomi Mi Box','0C1DAF':'Xiaomi Mi Box',
  '48B02D':'NVIDIA Shield','00044B':'NVIDIA Shield',
  'B03495':'Roku','DC3A5E':'Roku','C8394E':'Roku','D03E5C':'Roku',
  'B4755E':'Android TV Box','18B169':'Android TV Box','7C1EB3':'Android TV Box',
};

function detectModelFromMac(mac) {
  if (!mac) return null;
  const prefix = mac.replace(/[^a-fA-F0-9]/g, '').toUpperCase().slice(0, 6);
  return prefix.length >= 6 ? (MAC_OUI_SERVER[prefix] || null) : null;
}

async function saveAtivacao({ mac, deviceKey, plan, deviceName, deviceModel, userToken, expiresAt, paymentId, revendedor_id = null, nome_cliente_direct = null, usuario_id_direct = null, nome_revendedor = null, tipoPagamento = null }) {
  let usuario_id = usuario_id_direct;
  let nome_cliente = nome_cliente_direct;

  if (!usuario_id && userToken) {
    const session = sessions.get(userToken);
    if (session) {
      usuario_id = session.userId;
      if (!nome_cliente) nome_cliente = session.nome;
    }
  }

  const validade = expiresAt ? expiresAt.slice(0, 10) : 'vitalicio';
  const modelo = deviceModel || detectModelFromMac(mac);
  const agora = new Date().toISOString();

  // Campos comuns a update e insert
  const fields = {
    plano:             PLANO_MAP[plan] || 'anual',
    device_key:        deviceKey || null,
    nome_dispositivo:  deviceName || null,
    nome_cliente:      nome_cliente || null,
    usuario_id:        usuario_id || null,
    validade,
    pagamento_id:      paymentId || null,
    modelo_dispositivo: modelo,
    ativo:             true,
    atualizado_em:     agora,
    ...(revendedor_id ? { revendedor_id, nome_revendedor } : {})
  };

  // Tenta primeiro ATUALIZAR o registro existente (trial → pago)
  const { data: existing } = await supabase
    .from('ativacoes')
    .select('id, revendedor_id, nome_revendedor')
    .eq('mac_address', mac)
    .order('criado_em', { ascending: false })
    .limit(1)
    .maybeSingle();

  // Se o caller não forneceu revendedor_id mas o registro existente tem um, herda-o
  if (existing && !revendedor_id && existing.revendedor_id) {
    revendedor_id = existing.revendedor_id;
    if (!nome_revendedor) nome_revendedor = existing.nome_revendedor;
    // Atualiza fields com o revendedor herdado
    fields.revendedor_id  = revendedor_id;
    fields.nome_revendedor = nome_revendedor;
  }

  if (existing) {
    const { error } = await supabase
      .from('ativacoes')
      .update(fields)
      .eq('id', existing.id);
    if (error) console.error('Supabase ativacoes update error:', error.message, '| mac:', mac);
  } else {
    const { error } = await supabase.from('ativacoes').insert({
      ...fields,
      mac_address:  mac,
      termo_aceite: true,
      data_aceite:  agora,
      versao_termo: '1.0',
      criado_em:    agora
    });
    if (error) console.error('Supabase ativacoes insert error:', error.message, '| mac:', mac);
  }

  // Atualiza usuarios.ativo = true
  if (usuario_id) {
    const { error: userErr } = await supabase.from('usuarios')
      .update({ ativo: true })
      .eq('id', usuario_id);
    if (userErr) console.error('Supabase usuarios update error:', userErr.message, '| usuario_id:', usuario_id);
  }

  // Registrar no histórico
  const criadoPor = revendedor_id ? 'revendedor' : (userToken ? 'cliente' : 'sistema');
  insertHistoricoAtivacao({
    mac: mac, evento: 'ativacao',
    plano: PLANO_MAP[plan] || plan,
    validadeInicio: new Date().toISOString(),
    validadeFim: expiresAt || null,
    revendedorId: revendedor_id, nomeRevendedor: nome_revendedor,
    nomeCliente: nome_cliente, deviceKey: deviceKey || null,
    pagamentoId: paymentId || null, tipoPagamento: tipoPagamento || null,
    valorPago: paymentId && paymentId !== 'CREDITO REVENDA' ? (PLAN_PRICE_MAP[PLANO_MAP[plan]] || 0) : 0,
    criadoPor, observacao: null
  }).catch(e => console.error('[historico] ativacao:', e.message));

  // Registrar na tabela unificada de transações (apenas pagamentos reais)
  if (paymentId && paymentId !== 'CREDITO REVENDA') {
    const planoPortugues = PLANO_MAP[plan] || plan;
    const valorPago = PLAN_PRICE_MAP[planoPortugues] || 0;
    insertTransacao({
      pagamentoId:   paymentId,
      origem:        'ativacao',
      data:          new Date().toISOString(),
      status:        'pago',
      tipoPagamento: tipoPagamento,
      identificador: mac,
      nome:          nome_cliente,
      valorPago,
      revendedorId:  revendedor_id
    }).catch(e => console.error('[transacoes] ativacao:', e.message));
  }
}

function authenticateDevice(mac, deviceKey) {
  if (!mac || !deviceKey) return null;
  const device = devices.get(mac.toLowerCase());
  if (!device || device.deviceKey !== deviceKey) return null;
  return device;
}

async function authenticateDeviceAsync(mac, deviceKey) {
  if (!mac || !deviceKey) return null;
  // Fast path: dispositivos pagos/ativados já carregados em memória
  const local = devices.get(mac.toLowerCase());
  if (local && local.deviceKey === deviceKey) return local;
  // Fallback: trial e dispositivos registados apenas no Supabase
  const { data } = await supabase
    .from('ativacoes')
    .select('mac_address, device_key, plano, validade, ativo')
    .eq('mac_address', mac.toLowerCase())
    .eq('device_key', deviceKey)
    .eq('ativo', true)
    .order('criado_em', { ascending: false })
    .limit(1)
    .maybeSingle();
  if (!data) return null;
  const isVitalicio = !data.validade || data.validade === 'vitalicio';
  if (!isVitalicio && new Date(data.validade) < new Date()) return null;
  return { mac: data.mac_address, deviceKey: data.device_key, plan: data.plano, expiresAt: isVitalicio ? null : data.validade };
}

// ── Criptografia AES-256-CBC para senhas Xtream ───────────────────────────────
// SHA-256 do secret → sempre 32 bytes, independente do comprimento da env var
const PLAYLIST_SECRET = crypto.createHash('sha256')
  .update(process.env.PLAYLIST_SECRET || 'alpha-prime-default-secret')
  .digest();

function encryptText(plain) {
  if (!plain) return null;
  const iv = crypto.randomBytes(16);
  const cipher = crypto.createCipheriv('aes-256-cbc', PLAYLIST_SECRET, iv);
  const enc = Buffer.concat([cipher.update(plain, 'utf8'), cipher.final()]);
  return iv.toString('hex') + ':' + enc.toString('hex');
}

function decryptText(stored) {
  if (!stored) return null;
  try {
    const [ivHex, encHex] = stored.split(':');
    const decipher = crypto.createDecipheriv('aes-256-cbc', PLAYLIST_SECRET, Buffer.from(ivHex, 'hex'));
    return Buffer.concat([decipher.update(Buffer.from(encHex, 'hex')), decipher.final()]).toString('utf8');
  } catch { return null; }
}

// Converte linha da tabela listas → shape que o frontend/app espera
const TIPO_REVERSE = { m3u8: 'other', url: 'url', file: 'file', xtream: 'xtream' };
function dbRowToPlaylist(row, includeSecrets = false) {
  return {
    id:        row.lista_local_id || row.id,
    name:      row.nome_lista,
    type:      TIPO_REVERSE[row.tipo] || row.tipo,
    url:       row.url        || '',
    server:    row.servidor   || '',
    username:  row.utilizador_xtream || '',
    password:  includeSecrets ? (decryptText(row.senha_xtream_enc) || '') : undefined,
    epgUrl:    row.url_epg    || '',
    protected: !!row.protect_playlist,
    active:    row.status === 'ativa',
    status:    row.status,
    criado_em: row.criado_em,
    expira_em: row.expira_em,
  };
}

app.post('/api/playlists/login', async (req, res) => {
  const { mac, deviceKey } = req.body;
  const device = await authenticateDeviceAsync(mac, deviceKey);
  if (!device) {
    return res.status(401).json({ error: 'MAC Address ou Device Key incorretos, ou dispositivo ainda não ativado.' });
  }
  res.json({ success: true, plan: device.plan, expiresAt: device.expiresAt });
});

app.post('/api/playlists/create', async (req, res) => {
  const { mac, deviceKey, name, type } = req.body;
  if (!await authenticateDeviceAsync(mac, deviceKey)) {
    return res.status(401).json({ error: 'Sessão inválida. Faça login novamente.' });
  }
  if (!name || !type) {
    return res.status(400).json({ error: 'name e type são obrigatórios' });
  }

  const key = mac.toLowerCase();
  const localId = Date.now().toString(36) + Math.random().toString(36).slice(2, 6);

  // Buscar ativação para enriquecer o registo
  const { data: atvList } = await supabase
    .from('ativacoes')
    .select('id, nome_dispositivo, nome_cliente')
    .eq('mac_address', key)
    .eq('ativo', true)
    .order('criado_em', { ascending: false })
    .limit(1);
  const atv = Array.isArray(atvList) ? atvList[0] : null;

  // Determinar status: só ativa se não houver já outra ativa
  const { data: jaAtiva } = await supabase
    .from('listas').select('id').eq('mac_address', key).eq('status', 'ativa').limit(1);
  const status = (jaAtiva && jaAtiva.length > 0) ? 'inativa' : 'ativa';

  const tipoMap = { url: 'url', file: 'file', xtream: 'xtream', other: 'm3u8', m3u: 'url' };
  const tipo = tipoMap[type] || type;

  const row = {
    lista_local_id:    localId,
    ativacao_id:       atv?.id               || null,
    mac_address:       key,
    device_key:        deviceKey             || null,
    nome_dispositivo:  atv?.nome_dispositivo || null,
    nome_cliente:      atv?.nome_cliente     || null,
    nome_lista:        name,
    tipo,
    url:               req.body.url          || null,
    url_epg:           req.body.epgUrl       || null,
    ficheiro_nome:     req.body.fileName     || null,
    servidor:          req.body.server       || null,
    utilizador_xtream: req.body.username     || null,
    senha_xtream_enc:  req.body.password     ? encryptText(req.body.password) : null,
    protect_playlist:  !!req.body.protected,
    pin_hash:          req.body.protected && req.body.pin
                         ? crypto.createHash('sha256').update(req.body.pin).digest('hex')
                         : null,
    criado_em:         new Date().toISOString(),
    expira_em:         new Date(Date.now() + 30 * 24 * 3600 * 1000).toISOString(),
    status
  };

  const { error } = await supabase.from('listas').insert(row);
  if (error) {
    console.error('[listas] Erro no insert:', error.message);
    return res.status(500).json({ error: 'Erro ao guardar lista: ' + error.message });
  }
  console.log('[listas] Criada:', name, '| mac:', key, '| status:', status);

  // Manter cache local para authenticateDevice e parental (não é fonte de verdade)
  const localPlaylist = {
    id: localId, name, type,
    url: req.body.url || '', server: req.body.server || '',
    username: req.body.username || '', password: req.body.password || '',
    epgUrl: req.body.epgUrl || '', protected: !!req.body.protected,
    pin: req.body.protected ? req.body.pin || '' : ''
  };
  const list = playlists.get(key) || [];
  list.push(localPlaylist);
  playlists.set(key, list);
  if (status === 'ativa') activePlaylist.set(key, localId);
  saveState();

  res.json({ success: true, playlist: { ...localPlaylist, password: undefined, pin: undefined } });
});

// GET /api/playlists — Supabase como fonte de verdade
app.get('/api/playlists', async (req, res) => {
  if (!await authenticateDeviceAsync(req.query.mac, req.query.deviceKey)) {
    return res.status(401).json({ error: 'Sessão inválida. Faça login novamente.' });
  }
  const key = req.query.mac.toLowerCase();
  const { data, error } = await supabase
    .from('listas')
    .select('*')
    .eq('mac_address', key)
    .order('criado_em', { ascending: true });
  if (error) {
    console.error('[listas] Erro no GET:', error.message);
    return res.status(500).json({ error: 'Erro ao carregar listas' });
  }
  res.json((data || []).map(r => dbRowToPlaylist(r, false)));
});

// GET /api/playlists/sync — com credenciais completas (usado pelo app TV)
app.get('/api/playlists/sync', async (req, res) => {
  if (!await authenticateDeviceAsync(req.query.mac, req.query.deviceKey)) {
    return res.status(401).json({ error: 'Sessão inválida.' });
  }
  const key = req.query.mac.toLowerCase();
  const { data, error } = await supabase
    .from('listas')
    .select('*')
    .eq('mac_address', key)
    .order('criado_em', { ascending: true });
  if (error) return res.status(500).json({ error: 'Erro ao sincronizar' });
  res.json((data || []).map(r => dbRowToPlaylist(r, true)));
});

app.post('/api/playlists/:id/activate', async (req, res) => {
  const { mac, deviceKey } = req.body;
  if (!await authenticateDeviceAsync(mac, deviceKey)) {
    return res.status(401).json({ error: 'Sessão inválida. Faça login novamente.' });
  }

  const key = mac.toLowerCase();
  const localId = req.params.id;

  // Buscar a lista: primeiro por lista_local_id; se parecer UUID, tenta também por id
  const isUUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(localId);
  let query = supabase.from('listas').select('id').eq('mac_address', key);
  query = isUUID
    ? query.or(`lista_local_id.eq.${localId},id.eq.${localId}`)
    : query.eq('lista_local_id', localId);
  const { data: found } = await query.limit(1);
  if (!found || found.length === 0) {
    return res.status(404).json({ error: 'Lista não encontrada' });
  }
  const dbId = found[0].id;

  // Todas inativas → a selecionada ativa
  const { error: e1 } = await supabase.from('listas').update({ status: 'inativa' }).eq('mac_address', key);
  const { error: e2 } = await supabase.from('listas').update({ status: 'ativa'   }).eq('id', dbId);
  if (e1 || e2) {
    console.error('[listas] Erro ao ativar:', e1?.message || e2?.message);
    return res.status(500).json({ error: 'Erro ao ativar lista' });
  }

  activePlaylist.set(key, localId);
  saveState();

  res.json({ success: true, activeId: localId });
});

app.delete('/api/playlists/:id', async (req, res) => {
  const { mac, deviceKey } = req.body;
  if (!await authenticateDeviceAsync(mac, deviceKey)) {
    return res.status(401).json({ error: 'Sessão inválida. Faça login novamente.' });
  }

  const key = mac.toLowerCase();
  const localId = req.params.id;

  const isUUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(localId);
  let delQ = supabase.from('listas').delete().eq('mac_address', key);
  delQ = isUUID
    ? delQ.or(`lista_local_id.eq.${localId},id.eq.${localId}`)
    : delQ.eq('lista_local_id', localId);
  const { error } = await delQ;
  if (error) {
    console.error('[listas] Erro no DELETE:', error.message);
    return res.status(500).json({ error: 'Erro ao remover lista' });
  }
  console.log('[listas] Removida:', localId, '| mac:', key);

  // Atualizar cache local
  const list = playlists.get(key) || [];
  const next = list.filter(p => p.id !== localId);
  playlists.set(key, next);
  if (activePlaylist.get(key) === localId) activePlaylist.set(key, next[0]?.id || null);
  saveState();

  res.json({ success: true });
});

// ── Bloqueio de Canais por Senha — Supabase primary ───────────────────────────
async function getParentalRecord(mac) {
  const { data } = await supabase.from('parental').select('*').eq('mac_address', mac).limit(1);
  return Array.isArray(data) ? (data[0] || null) : null;
}

async function upsertParental(mac, fields) {
  // Preserva campos existentes (ex.: não apaga canais_bloqueados ao mudar pin)
  const { data: existing } = await supabase
    .from('parental').select('*').eq('mac_address', mac).limit(1).maybeSingle();

  const { data: atv } = await supabase
    .from('ativacoes').select('nome_dispositivo, nome_cliente')
    .eq('mac_address', mac).eq('ativo', true)
    .order('criado_em', { ascending: false }).limit(1).maybeSingle();

  const record = {
    mac_address:       mac,
    nome_dispositivo:  existing?.nome_dispositivo || atv?.nome_dispositivo || null,
    nome_cliente:      existing?.nome_cliente     || atv?.nome_cliente     || null,
    canais_bloqueados: existing?.canais_bloqueados ?? [],
    pin_hash:          existing?.pin_hash         ?? null,
    criado_em:         existing?.criado_em        || new Date().toISOString(),
    ...fields,
    atualizado_em:     new Date().toISOString(),
  };

  const { error } = await supabase
    .from('parental')
    .upsert(record, { onConflict: 'mac_address' });

  if (error) console.error('[parental] upsert erro:', error.message, '| fields:', JSON.stringify(fields));
  return error;
}

app.get('/api/parental', async (req, res) => {
  if (!await authenticateDeviceAsync(req.query.mac, req.query.deviceKey)) {
    return res.status(401).json({ error: 'Sessão inválida. Faça login novamente.' });
  }
  const record = await getParentalRecord(req.query.mac.toLowerCase());
  res.json({ hasPin: !!record?.pin_hash, lockedCategories: record?.canais_bloqueados || [] });
});

app.get('/api/parental/sync', async (req, res) => {
  if (!await authenticateDeviceAsync(req.query.mac, req.query.deviceKey)) {
    return res.status(401).json({ error: 'Sessão inválida.' });
  }
  const record = await getParentalRecord(req.query.mac.toLowerCase());
  res.json({ pinHash: record?.pin_hash || '', lockedCategories: record?.canais_bloqueados || [] });
});

app.post('/api/parental/set-pin', async (req, res) => {
  const { mac, deviceKey, currentPin, newPin } = req.body;
  if (!await authenticateDeviceAsync(mac, deviceKey)) {
    return res.status(401).json({ error: 'Sessão inválida. Faça login novamente.' });
  }
  if (!newPin || newPin.length < 4) {
    return res.status(400).json({ error: 'A senha deve ter pelo menos 4 dígitos' });
  }

  const key = mac.toLowerCase();
  const record = await getParentalRecord(key);

  if (record?.pin_hash) {
    if (!currentPin || hashPin(currentPin) !== record.pin_hash) {
      return res.status(401).json({ error: 'Senha atual incorreta' });
    }
  }

  const err = await upsertParental(key, { pin_hash: hashPin(newPin) });
  if (err) { console.error('[parental] Erro set-pin:', err.message); return res.status(500).json({ error: 'Erro ao salvar senha' }); }

  const state = parental.get(key) || { pinHash: '', lockedCategories: [] };
  state.pinHash = hashPin(newPin);
  parental.set(key, state); saveState();
  res.json({ success: true });
});

app.post('/api/parental/unlock', async (req, res) => {
  const { mac, deviceKey, category, pin } = req.body;
  if (!await authenticateDeviceAsync(mac, deviceKey)) {
    return res.status(401).json({ error: 'Sessão inválida. Faça login novamente.' });
  }

  const key = mac.toLowerCase();
  const record = await getParentalRecord(key);
  if (!record?.pin_hash) return res.status(400).json({ error: 'Nenhuma senha configurada para este dispositivo' });
  if (!pin || hashPin(pin) !== record.pin_hash) return res.status(401).json({ error: 'Senha incorreta' });

  const newCats = (record.canais_bloqueados || []).filter(c => c !== category);
  const err = await upsertParental(key, { canais_bloqueados: newCats });
  if (err) { console.error('[parental] Erro unlock:', err.message); return res.status(500).json({ error: 'Erro ao desbloquear' }); }

  const state = parental.get(key) || { pinHash: record.pin_hash, lockedCategories: [] };
  state.lockedCategories = newCats;
  parental.set(key, state); saveState();
  res.json({ success: true, lockedCategories: newCats });
});

app.post('/api/parental/categories', async (req, res) => {
  const { mac, deviceKey, lockedCategories } = req.body;
  if (!await authenticateDeviceAsync(mac, deviceKey)) {
    return res.status(401).json({ error: 'Sessão inválida. Faça login novamente.' });
  }

  const key = mac.toLowerCase();
  const cats = Array.isArray(lockedCategories) ? lockedCategories : [];
  const err = await upsertParental(key, { canais_bloqueados: cats });
  if (err) { console.error('[parental] Erro categories:', err.message); return res.status(500).json({ error: 'Erro ao salvar categorias' }); }

  const state = parental.get(key) || { pinHash: '', lockedCategories: [] };
  state.lockedCategories = cats;
  parental.set(key, state); saveState();
  res.json({ success: true });
});

app.post('/api/device/check', async (req, res) => {
  const { mac, deviceKey } = req.body;
  const macNorm = (mac || '').toLowerCase();

  const { data, error } = await supabase
    .from('ativacoes')
    .select('plano, validade, ativo')
    .eq('mac_address', macNorm)
    .eq('ativo', true)
    .order('criado_em', { ascending: false })
    .limit(1)
    .maybeSingle();

  if (error) { console.error('[device/check] supabase error:', error.message); }

  if (!data) return res.json({ activated: false, reason: 'not_found' });

  const validade = data.validade;
  const isVitalicio = !validade || validade === 'vitalicio';
  if (!isVitalicio && new Date(validade) < new Date()) {
    return res.json({ activated: false, reason: 'expired' });
  }

  res.json({ activated: true, plan: data.plano, expiresAt: isVitalicio ? null : validade });
});

// ── Favoritos ─────────────────────────────────────────────────────────────────
app.get('/api/favorites/sync', async (req, res) => {
  if (!await authenticateDeviceAsync(req.query.mac, req.query.deviceKey)) {
    return res.status(401).json({ error: 'Sessão inválida.' });
  }
  const { data, error } = await supabase
    .from('favoritos')
    .select('item_id, tipo, nome, url, logo, grupo')
    .eq('mac_address', req.query.mac.toLowerCase())
    .order('criado_em', { ascending: false });
  if (error) return res.status(500).json({ error: error.message });
  res.json(data || []);
});

app.post('/api/favorites/toggle', async (req, res) => {
  const { mac, deviceKey, item_id, tipo, nome, url, logo, grupo } = req.body;
  if (!await authenticateDeviceAsync(mac, deviceKey)) {
    return res.status(401).json({ error: 'Sessão inválida.' });
  }
  if (!item_id || !tipo) return res.status(400).json({ error: 'item_id e tipo são obrigatórios' });

  const key = mac.toLowerCase();
  const { data: existing } = await supabase
    .from('favoritos').select('id')
    .eq('mac_address', key).eq('item_id', item_id).eq('tipo', tipo)
    .maybeSingle();

  if (existing) {
    await supabase.from('favoritos').delete().eq('id', existing.id);
    return res.json({ action: 'removed' });
  }

  const { error } = await supabase.from('favoritos').insert({
    mac_address: key, item_id, tipo,
    nome: nome || null, url: url || null,
    logo: logo || null, grupo: grupo || null,
    criado_em: new Date().toISOString()
  });
  if (error) return res.status(500).json({ error: error.message });
  res.json({ action: 'added' });
});

// Registra um dispositivo novo no período de trial (chamado pelo app Android
// na primeira abertura). Idempotente: se já existe registro trial para esse
// mac_address, retorna o existente sem inserir duplicata.
app.post('/api/device/register-trial', async (req, res) => {
  const { mac_address, devicekey, modelo_dispositivo } = req.body;
  if (!mac_address) {
    return res.status(400).json({ error: 'mac_address é obrigatório' });
  }

  // Verifica se já existe QUALQUER registro para este dispositivo (trial ou pago)
  // Se existir (mesmo plano='anual'), não insere trial para não criar duplicata
  // que causaria o app voltar à tela de ativação após pagamento.
  const { data: existing } = await supabase
    .from('ativacoes')
    .select('validade, plano')
    .eq('mac_address', mac_address)
    .order('criado_em', { ascending: false })
    .limit(1)
    .maybeSingle();

  if (existing) {
    console.log('[register-trial] dispositivo já registrado (plano:', existing.plano, '):', mac_address);
    return res.json({ success: true, validade: existing.validade });
  }

  const validade = new Date(Date.now() + 7 * 24 * 60 * 60 * 1000)
    .toISOString().slice(0, 10); // "YYYY-MM-DD"

  const { error } = await supabase.from('ativacoes').insert({
    mac_address,
    device_key:         devicekey || null,
    plano:              'trial',
    validade,
    criado_em:          new Date().toISOString(),
    ativo:              true,
    modelo_dispositivo: modelo_dispositivo || null,
    nome_dispositivo:   null,
    nome_cliente:       null,
    usuario_id:         null,
    pagamento_id:       null,
    nome_revendedor:    null,
    revendedor_id:      null,
  });

  if (error) {
    console.error('[register-trial] erro:', error.message);
    return res.status(500).json({ error: error.message });
  }

  console.log('[register-trial] registrado:', mac_address, '| validade:', validade);
  res.json({ success: true, validade });
});

// ══════════════════════════════════════════════════════════════════════════════
// RESELLER SYSTEM
// ══════════════════════════════════════════════════════════════════════════════

const resellers = new Map();       // id -> { username, passwordHash, credits, parentId, active, createdAt }
const resellerTokens = new Map();  // token -> resellerId
const resellerDevices = new Map(); // resellerId -> [{ mac, comment, model, activationExpires, createdAt }]
const resellerTxns = new Map();    // resellerId -> [{ id, method, transactionId, creditCount, status, priceInCents, createdAt }]
const resellerActLog = new Map();  // resellerId -> [{ mac, comment, plan, createdAt }]
const resellerLinks = new Map();   // resellerId -> [{ id, code, name, plan, activations, createdAt }]
const resellerWithdrawals = new Map(); // resellerId -> [{ id, amount, method, key, status, createdAt, processedAt }]

let CREDIT_PACKAGES = [
  { id: 'inicial',      name: 'Inicial',      activations: 10,  priceInCents: 12000  },
  { id: 'essencial',    name: 'Essencial',     activations: 50,  priceInCents: 50000  },
  { id: 'avancado',     name: 'Avançado',      activations: 100, priceInCents: 90000  },
  { id: 'profissional', name: 'Profissional',  activations: 200, priceInCents: 160000 },
  { id: 'premium',      name: 'Premium',       activations: 300, priceInCents: 200000 },
  { id: 'empresarial',  name: 'Empresarial',   activations: 500, priceInCents: 300000 },
];

async function loadPackagesFromDB() {
  const { data, error } = await supabase
    .from('pacotes_credito')
    .select('id, nome, ativacoes, valor_centavos')
    .eq('ativo', true)
    .order('ordem', { ascending: true });
  if (error || !data?.length) { console.warn('[pacotes] Tabela não encontrada ou vazia — usando fallback em memória'); return; }
  CREDIT_PACKAGES = data.map(p => ({
    id: p.id, name: p.nome, activations: p.ativacoes, priceInCents: p.valor_centavos
  }));
  console.log(`[pacotes] ${CREDIT_PACKAGES.length} pacotes carregados do banco`);
}
loadPackagesFromDB();

const RESELLER_PLAN_DAYS = { monthly: 30, quarterly: 90, semiannual: 180, yearly: 365, lifetime: null };

function hashPassword(p) { return crypto.createHash('sha256').update(p).digest('hex'); }
function genToken() { return crypto.randomBytes(32).toString('hex'); }
function genId() { return Date.now().toString(36) + Math.random().toString(36).slice(2, 8); }
function genCode() { return Math.random().toString(36).slice(2, 10).toUpperCase(); }

function saveResellerState() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => {
    const snap = {
      devices: [...devices.entries()],
      payments: [...payments.entries()],
      playlists: [...playlists.entries()],
      activePlaylist: [...activePlaylist.entries()],
      parental: [...parental.entries()],
      resellers: [...resellers.entries()],
      resellerDevices: [...resellerDevices.entries()],
      resellerTxns: [...resellerTxns.entries()],
      resellerActLog: [...resellerActLog.entries()],
      resellerLinks: [...resellerLinks.entries()],
      resellerWithdrawals: [...resellerWithdrawals.entries()],
    };
    fs.writeFile(DB_FILE, JSON.stringify(snap, null, 2), err => {
      if (err) console.error('Erro ao salvar:', err.message);
    });
  }, 150);
}

// Seed default reseller
(function seedDefaultReseller() {
  if ([...resellers.values()].some(r => r.username === 'reseller')) return;
  const id = genId();
  resellers.set(id, {
    username: 'reseller', passwordHash: hashPassword('123456'),
    credits: 10, parentId: null, active: true, createdAt: new Date().toISOString(),
    earnings: 0
  });
})();

// Middleware: authenticate reseller token
function authReseller(req, res, next) {
  const token = req.headers['x-reseller-token'];
  const resellerId = resellerTokens.get(token);
  if (!resellerId || !resellers.get(resellerId)) return res.status(401).json({ error: 'Sessão inválida' });
  req.resellerId = resellerId;
  req.reseller = resellers.get(resellerId);
  next();
}

// ── AUTH ──────────────────────────────────────────────────────────────────────
app.post('/api/reseller/login', async (req, res) => {
  const { username, password } = req.body;
  if (!username || !password) return res.status(400).json({ error: 'Email e senha são obrigatórios' });

  // 1. Check legacy local resellers Map (SHA256)
  const localEntry = [...resellers.entries()].find(([, r]) => r.username === username);
  if (localEntry) {
    const [id, reseller] = localEntry;
    if (reseller.passwordHash !== hashPassword(password))
      return res.status(401).json({ error: 'Credenciais inválidas' });
    if (!reseller.active) return res.status(403).json({ error: 'Conta desativada' });
    const token = genToken();
    resellerTokens.set(token, id);
    return res.json({ resellerId: id, username: reseller.username, token, role: reseller.role || 'revendedor' });
  }

  // 2. Check Supabase usuarios where role = 'revendedor'
  try {
    const { data: rows, error: loginQueryErr } = await supabase
      .from('usuarios')
      .select('id, nome, email, senha_hash, ativo, role, data_autoexclusao')
      .eq('email', username.toLowerCase().trim())
      .in('role', ['revendedor', 'administrador'])
      .limit(1);

    // Fallback sem a coluna data_autoexclusao (caso ainda não exista no banco)
    let user;
    if (loginQueryErr) {
      const { data: rows2 } = await supabase
        .from('usuarios')
        .select('id, nome, email, senha_hash, ativo, role')
        .eq('email', username.toLowerCase().trim())
        .in('role', ['revendedor', 'administrador'])
        .limit(1);
      user = rows2?.[0];
    } else {
      user = rows?.[0];
    }

    if (!user) return res.status(401).json({ error: 'Credenciais inválidas' });
    if (user.data_autoexclusao) return res.status(403).json({ error: 'Conta autoexcluída. Para mais dúvidas, contacte o suporte.' });
    if (!user.ativo) return res.status(403).json({ error: 'Conta aguardando aprovação' });

    const match = await bcrypt.compare(password, user.senha_hash);
    if (!match) return res.status(401).json({ error: 'Credenciais inválidas' });

    // Ensure local Map entry exists for this Supabase reseller
    if (!resellers.has(user.id)) {
      resellers.set(user.id, {
        username: user.email, nome: user.nome, passwordHash: null,
        credits: 0, parentId: null, active: true, role: user.role,
        createdAt: new Date().toISOString(), earnings: 0, supabaseId: user.id
      });
    } else {
      resellers.get(user.id).role = user.role;
    }

    const token = genToken();
    resellerTokens.set(token, user.id);
    return res.json({ resellerId: user.id, username: user.nome || user.email, token, role: user.role });
  } catch (e) {
    console.error('Erro login revendedor:', e.message);
    return res.status(500).json({ error: 'Erro interno' });
  }
});

app.post('/api/reseller/register', async (req, res) => {
  const { nome, email, senha, confirmar_senha, telefone, pais, data_nascimento } = req.body;
  if (!nome || !email || !senha) return res.status(400).json({ error: 'Nome, email e senha são obrigatórios' });
  if (senha.length < 6) return res.status(400).json({ error: 'Senha mínima de 6 caracteres' });
  if (senha !== confirmar_senha) return res.status(400).json({ error: 'As senhas não coincidem' });

  try {
    const senhaHash = await bcrypt.hash(senha, 12);
    const { error } = await supabase.from('usuarios').insert([{
      nome, email: email.toLowerCase().trim(), senha_hash: senhaHash,
      telefone: telefone || null, pais: pais || null,
      data_nascimento: data_nascimento || null,
      role: 'revendedor', ativo: false, criado_em: new Date().toISOString()
    }]);

    if (error) {
      if (error.code === '23505') return res.status(409).json({ error: 'Este email já está cadastrado' });
      throw error;
    }
    res.json({ success: true, message: 'Cadastro enviado! Aguarde a aprovação.' });
  } catch (e) {
    console.error('Erro cadastro revendedor:', e.message);
    res.status(500).json({ error: 'Erro ao criar conta' });
  }
});

app.post('/api/reseller/forgot-password', async (req, res) => {
  const { email } = req.body;
  if (!email) return res.status(400).json({ error: 'Email é obrigatório' });

  try {
    const { data: user } = await supabase
      .from('usuarios')
      .select('id, nome, email')
      .eq('email', email.toLowerCase().trim())
      .in('role', ['revendedor', 'administrador'])
      .single();

    // Always return success to avoid user enumeration
    res.json({ success: true });

    if (!user) return;

    const token = crypto.randomBytes(32).toString('hex');
    resetTokens.set(token, { email: user.email, expiresAt: Date.now() + 3600_000 });

    const resetLink = `${APP_BASE_URL}/painel.html?reset=${token}`;

    await resend.emails.send({
      from: 'Alpha Prime Suporte <suporte@alphaprimetv.com>',
      to: user.email,
      subject: 'Redefinição de senha — Painel Alpha Prime',
      html: buildResetEmailHtml(user.nome || 'Revendedor', resetLink)
    });
  } catch (err) {
    console.error('[reseller/forgot-password] Erro:', err.message);
    if (!res.headersSent) res.status(500).json({ error: 'Erro ao processar solicitação.' });
  }
});

// ── PACKAGES ──────────────────────────────────────────────────────────────────
app.get('/api/reseller/packages', authReseller, async (req, res) => {
  const { data, error } = await supabase
    .from('pacotes_credito')
    .select('id, nome, ativacoes, valor_centavos')
    .eq('ativo', true)
    .order('ordem', { ascending: true });
  if (error || !data?.length) return res.json(CREDIT_PACKAGES); // fallback
  res.json(data.map(p => ({ id: p.id, name: p.nome, activations: p.ativacoes, priceInCents: p.valor_centavos })));
});

// Admin: listar todos (inclusive inativos)
app.get('/api/reseller/packages/admin', authReseller, async (req, res) => {
  const { data, error } = await supabase
    .from('pacotes_credito')
    .select('id, nome, ativacoes, valor_centavos, ativo, ordem')
    .order('ordem', { ascending: true });
  if (error) return res.status(500).json({ error: error.message });
  res.json(data || []);
});

// Admin: criar pacote
app.post('/api/reseller/packages/admin', authReseller, async (req, res) => {
  const { nome, ativacoes, valor, ordem } = req.body;
  if (!nome || !ativacoes || !valor) return res.status(400).json({ error: 'nome, ativacoes e valor são obrigatórios' });
  const id = nome.toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/\s+/g, '_').replace(/[^a-z0-9_]/g, '');
  const valor_centavos = Math.round(parseFloat(valor) * 100);
  const { data, error } = await supabase.from('pacotes_credito').insert({
    id, nome, ativacoes: parseInt(ativacoes), valor_centavos, ativo: true, ordem: parseInt(ordem) || 1
  }).select().single();
  if (error) {
    if (error.code === '23505') return res.status(409).json({ error: 'Já existe um pacote com esse nome (id duplicado)' });
    return res.status(500).json({ error: error.message });
  }
  await loadPackagesFromDB();
  res.json({ success: true, data });
});

// Admin: atualizar pacote
app.put('/api/reseller/packages/admin/:id', authReseller, async (req, res) => {
  const { nome, ativacoes, valor, ordem } = req.body;
  if (!nome || !ativacoes || !valor) return res.status(400).json({ error: 'nome, ativacoes e valor são obrigatórios' });
  const valor_centavos = Math.round(parseFloat(valor) * 100);
  const { error } = await supabase.from('pacotes_credito').update({
    nome, ativacoes: parseInt(ativacoes), valor_centavos, ordem: parseInt(ordem) || 1
  }).eq('id', req.params.id);
  if (error) return res.status(500).json({ error: error.message });
  await loadPackagesFromDB();
  res.json({ success: true });
});

// Admin: toggle ativo
app.patch('/api/reseller/packages/admin/:id/toggle', authReseller, async (req, res) => {
  const { ativo } = req.body;
  const { error } = await supabase.from('pacotes_credito').update({ ativo: !!ativo }).eq('id', req.params.id);
  if (error) return res.status(500).json({ error: error.message });
  await loadPackagesFromDB();
  res.json({ success: true });
});

// Admin: excluir pacote
app.delete('/api/reseller/packages/admin/:id', authReseller, async (req, res) => {
  const { error } = await supabase.from('pacotes_credito').delete().eq('id', req.params.id);
  if (error) return res.status(500).json({ error: error.message });
  await loadPackagesFromDB();
  res.json({ success: true });
});

// ── DASHBOARD ─────────────────────────────────────────────────────────────────
app.get('/api/reseller/dashboard', authReseller, async (req, res) => {
  const rid = req.resellerId;
  const r = req.reseller;
  const now = new Date();
  const startOfDay = new Date(now); startOfDay.setHours(0,0,0,0);
  const startOfMonth = new Date(now.getFullYear(), now.getMonth(), 1);

  // Ativações: busca direto no Supabase ativo=true filtrado por revendedor_id
  const { data: ativacoes } = await supabase
    .from('ativacoes')
    .select('criado_em, mac_address')
    .eq('revendedor_id', rid)
    .eq('ativo', true);
  const atv = ativacoes || [];
  const actTotal = atv.length;
  const actMonth = atv.filter(a => new Date(a.criado_em) >= startOfMonth).length;
  const actToday = atv.filter(a => new Date(a.criado_em) >= startOfDay).length;

  // Listas stats: conta ativas e a vencer hoje para este revendedor
  const macsDash = [...new Set(atv.map(a => a.mac_address).filter(Boolean))];
  let listasAtivas = 0, listasVencendoHoje = 0;
  if (macsDash.length) {
    const endOfDay = new Date(startOfDay); endOfDay.setHours(23, 59, 59, 999);
    const [{ count: cAtivas }, { count: cHoje }] = await Promise.all([
      supabase.from('listas').select('id', { count: 'exact', head: true })
        .in('mac_address', macsDash).eq('status', 'ativa'),
      supabase.from('listas').select('id', { count: 'exact', head: true })
        .in('mac_address', macsDash).eq('status', 'ativa')
        .gte('expira_em', startOfDay.toISOString())
        .lte('expira_em', endOfDay.toISOString()),
    ]);
    listasAtivas = cAtivas ?? 0;
    listasVencendoHoje = cHoje ?? 0;
  }

  // Saldo: busca saldo atual + compras do mês/hoje em uma única query
  const { data: saldoRows } = await supabase
    .from('saldo')
    .select('saldo_resultante, quantidade, tipo, criado_em')
    .eq('revendedor_id', rid)
    .order('criado_em', { ascending: false });

  const rows = saldoRows || [];

  // Saldo atual = saldo_resultante da linha mais recente (já ordenado DESC)
  const credits = rows[0]?.saldo_resultante ?? r.credits;
  if (r.credits !== credits) r.credits = credits; // sincroniza in-memory

  // Créditos comprados este mês e hoje (apenas entradas tipo 'compra')
  const compras = rows.filter(x => x.tipo === 'compra');
  const crMonth = compras
    .filter(x => new Date(x.criado_em) >= startOfMonth)
    .reduce((s, x) => s + x.quantidade, 0);
  const crToday = compras
    .filter(x => new Date(x.criado_em) >= startOfDay)
    .reduce((s, x) => s + x.quantidade, 0);

  const subs = [...resellers.values()].filter(s => s.parentId === rid);
  const subsToday = subs.filter(s => new Date(s.createdAt) >= startOfDay).length;
  const subsMonth = subs.filter(s => new Date(s.createdAt) >= startOfMonth).length;

  const links = resellerLinks.get(rid) || [];
  const linksToday = links.filter(l => new Date(l.createdAt) >= startOfDay).length;
  const actByLinks = links.reduce((s, l) => s + l.activations, 0);

  res.json({
    credits,
    activations: { total: actTotal, month: actMonth, today: actToday },
    // total = saldo disponível agora; month/today = comprado nesse período
    creditsSpent: { total: credits, month: crMonth, today: crToday },
    subresellers: { total: subs.length, month: subsMonth, today: subsToday },
    links: { total: links.length, today: linksToday, totalActivated: actByLinks, activatedToday: 0 },
    listas: { ativas: listasAtivas, vencendoHoje: listasVencendoHoje },
    earnings: await (async () => {
      const todayIso  = startOfDay.toISOString();
      const monthIso  = startOfMonth.toISOString();
      const prevStart = new Date(now.getFullYear(), now.getMonth() - 1, 1).toISOString();
      const nextIso   = new Date(now.getFullYear(), now.getMonth() + 1, 1).toISOString();

      const soma = (rows) => (rows || []).reduce((s, r) => s + (parseFloat(r.valor_pago) || 0), 0);

      const [{ data: rHoje }, { data: rMes }, { data: rPrev }] = await Promise.all([
        supabase.from('transacoes').select('valor_pago').gte('data', todayIso).lt('data', nextIso),
        supabase.from('transacoes').select('valor_pago').gte('data', monthIso).lt('data', nextIso),
        supabase.from('transacoes').select('valor_pago').gte('data', prevStart).lt('data', monthIso),
      ]);

      return { mes: soma(rMes), anterior: soma(rPrev), hoje: soma(rHoje) };
    })(),
  });
});

// ── DEVICES ───────────────────────────────────────────────────────────────────

// Lookup por MAC (auto-preencher modal)
// Histórico de um dispositivo por MAC
app.get('/api/reseller/devices/historico', authReseller, async (req, res) => {
  const mac = (req.query.mac || '').toLowerCase().trim();
  if (!mac) return res.status(400).json({ error: 'MAC obrigatório' });

  const isAdmin = req.reseller?.role === 'administrador';
  let q = supabase.from('historico_ativacoes')
    .select('id, evento, plano, validade_inicio, validade_fim, revendedor_id, nome_revendedor, nome_cliente, pagamento_id, tipo_pagamento, valor_pago, criado_por, observacao, criado_em')
    .eq('mac_address', mac)
    .order('criado_em', { ascending: false });

  if (!isAdmin) q = q.eq('revendedor_id', req.resellerId);

  const { data, error } = await q;
  if (error) return res.status(500).json({ error: error.message });
  res.json(data || []);
});

app.get('/api/reseller/devices/lookup', authReseller, async (req, res) => {
  const mac = (req.query.mac || '').toLowerCase().trim();
  if (!mac) return res.status(400).json({ error: 'MAC obrigatório' });

  const { data } = await supabase
    .from('ativacoes')
    .select('nome_cliente, nome_dispositivo, modelo_dispositivo, validade, plano, device_key, mac_address, ativo')
    .eq('mac_address', mac)
    .order('criado_em', { ascending: false })
    .limit(1);

  if (!data || data.length === 0) return res.status(404).json({ error: 'Dispositivo não encontrado' });
  res.json(data[0]);
});

// Lista dispositivos — admin vê todos com filtros; revendedor vê só os seus
app.get('/api/reseller/devices', authReseller, async (req, res) => {
  const isAdmin = req.reseller?.role === 'administrador';
  const { filtro, revendedor_id } = req.query; // filtro: 'todos'|'autonomo'|'revendedor'

  let q = supabase
    .from('ativacoes')
    .select('id, nome_cliente, mac_address, nome_dispositivo, validade, criado_em, modelo_dispositivo, ativo, device_key, plano, revendedor_id, nome_revendedor')
    .order('criado_em', { ascending: false });

  if (isAdmin) {
    if (filtro === 'autonomo') q = q.is('revendedor_id', null);
    else if (filtro === 'revendedor' && revendedor_id) q = q.eq('revendedor_id', revendedor_id);
    // filtro 'todos' ou ausente → sem restrição
  } else {
    q = q.eq('revendedor_id', req.resellerId);
  }

  const { data, error } = await q;
  if (error) {
    console.error('Erro ao buscar dispositivos:', error.message);
    return res.json(resellerDevices.get(req.resellerId) || []);
  }
  res.json(data || []);
});

app.post('/api/reseller/devices/activate', authReseller, async (req, res) => {
  const r = req.reseller;
  if (r.credits < 1) return res.status(400).json({ error: 'Créditos insuficientes' });

  const { mac, comment, plan, deviceKey, deviceName, detectedModel } = req.body;
  const macKey = (mac || '').toLowerCase();
  if (!macKey) return res.status(400).json({ error: 'MAC Address obrigatório' });

  const days = RESELLER_PLAN_DAYS[plan];
  const expiresAt = days ? new Date(Date.now() + days * 86400000).toISOString() : 'lifetime';
  const resolvedModel = detectedModel || detectModelFromMac(mac);

  devices.set(macKey, { active: true, plan: plan||'yearly', expiresAt: days ? expiresAt : null, deviceKey: deviceKey||'' });

  const devList = resellerDevices.get(req.resellerId) || [];
  const existing = devList.findIndex(d => d.mac === macKey);
  const entry = {
    mac: macKey, comment: comment||'', model: resolvedModel||'',
    activationExpires: days ? expiresAt : 'lifetime',
    createdAt: new Date().toISOString()
  };
  if (existing >= 0) devList[existing] = entry; else devList.push(entry);
  resellerDevices.set(req.resellerId, devList);

  const actLog = resellerActLog.get(req.resellerId) || [];
  actLog.unshift({ mac: macKey, comment: comment||'', plan, createdAt: new Date().toISOString() });
  resellerActLog.set(req.resellerId, actLog);

  r.credits -= 1;
  saveResellerState();

  saveAtivacao({
    mac: macKey,
    deviceKey: deviceKey || '',
    plan: plan || 'yearly',
    deviceName: deviceName || null,
    deviceModel: resolvedModel,
    userToken: null,
    expiresAt: days ? expiresAt : null,
    paymentId: 'CREDITO REVENDA',
    revendedor_id: req.resellerId,
    nome_cliente_direct: comment || null,
    usuario_id_direct: req.reseller.supabaseId || null,
    nome_revendedor: req.reseller.nome || req.reseller.username || null
  }).catch(e => console.error('Erro ao gravar ativação reseller:', e));

  // Débito no saldo
  insertSaldo({
    revendedorId:   req.resellerId,
    nomeRevendedor: req.reseller.nome || req.reseller.username || null,
    tipo:           'uso',
    quantidade:     -1,
    referenciaId:   macKey,
    descricao:      `Ativação ${macKey}${comment ? ' — ' + comment : ''}`
  }).catch(e => console.error('Erro ao debitar saldo:', e));

  res.json({ success: true, expiresAt });
});

app.post('/api/reseller/devices/renew', authReseller, async (req, res) => {
  const { mac, plan, deviceKey, deviceName, nomeCliente } = req.body;
  const macKey = (mac || '').toLowerCase();
  if (!macKey) return res.status(400).json({ error: 'MAC obrigatório' });

  const days = RESELLER_PLAN_DAYS[plan];
  if (days === undefined) return res.status(400).json({ error: 'Plano inválido' });

  const saldo = await getSaldoAtual(req.resellerId);
  if (saldo < 1) return res.status(400).json({ error: 'Créditos insuficientes' });

  const expiresAt = plan === 'lifetime' ? null : new Date(Date.now() + days * 86400000).toISOString();

  const agora = new Date().toISOString();
  const updateFields = {
    ativo: true,
    validade: expiresAt,
    plano: PLANO_MAP[plan] || plan,
    atualizado_em: agora
  };
  if (nomeCliente) updateFields.nome_cliente     = nomeCliente;
  if (deviceKey)   updateFields.device_key       = deviceKey;
  if (deviceName)  updateFields.nome_dispositivo = deviceName;

  const isAdmin = req.reseller?.role === 'administrador';
  const criadoPor = isAdmin ? 'administrador' : 'revendedor';

  // Admin pode renovar qualquer dispositivo (próprio, de revendedor ou autônomo)
  let q = supabase.from('ativacoes').update(updateFields).eq('mac_address', macKey);
  if (!isAdmin) q = q.eq('revendedor_id', req.resellerId);

  const { data: updated, error } = await q.select('id, nome_cliente, revendedor_id, nome_revendedor');
  if (error) return res.status(500).json({ error: error.message });

  // Se nenhum registro foi atualizado, cria uma nova ativação
  if (!updated?.length) {
    await saveAtivacao({
      mac: macKey, deviceKey: deviceKey || '', plan: plan || 'yearly',
      deviceName: deviceName || null, deviceModel: null, userToken: null,
      expiresAt: expiresAt, paymentId: 'CREDITO REVENDA',
      revendedor_id: req.resellerId,
      nome_cliente_direct: nomeCliente || null,
      usuario_id_direct: req.reseller.supabaseId || null,
      nome_revendedor: req.reseller.nome || req.reseller.username || null
    }).catch(e => console.error('Erro ao inserir ativação na renovação:', e));
  } else {
    // Registrar renovação no histórico
    const row = updated[0];
    insertHistoricoAtivacao({
      mac: macKey, evento: 'renovacao',
      plano: PLANO_MAP[plan] || plan,
      validadeInicio: agora,
      validadeFim: expiresAt || null,
      revendedorId: req.resellerId,
      nomeRevendedor: req.reseller.nome || req.reseller.username || null,
      nomeCliente: nomeCliente || row.nome_cliente || null,
      deviceKey: deviceKey || null,
      pagamentoId: null, tipoPagamento: 'credito_revenda', valorPago: 0,
      criadoPor, observacao: null
    }).catch(e => console.error('[historico] renovacao:', e.message));
  }

  await insertSaldo({
    revendedorId:   req.resellerId,
    nomeRevendedor: req.reseller.nome || req.reseller.username || null,
    tipo:           'uso',
    quantidade:     -1,
    referenciaId:   macKey,
    descricao:      `Renovação ${macKey}${nomeCliente ? ' — ' + nomeCliente : ''}`
  }).catch(e => console.error('Erro ao debitar saldo na renovação:', e));

  res.json({ success: true, expiresAt });
});

app.post('/api/reseller/devices/add-existing', authReseller, async (req, res) => {
  const { mac, comment, deviceName, modelo } = req.body;
  const macKey = (mac || '').toLowerCase();
  if (!macKey) return res.status(400).json({ error: 'MAC Address obrigatório' });

  // Verificar se já tem revendedor associado
  const { data: existente } = await supabase
    .from('ativacoes')
    .select('id, revendedor_id')
    .eq('mac_address', macKey)
    .eq('ativo', true)
    .limit(1);

  if (existente?.length) {
    const revId = existente[0].revendedor_id;
    if (revId && revId === req.resellerId)
      return res.status(400).json({ error: 'Dispositivo já vinculado à sua conta' });
    if (revId)
      return res.status(400).json({ error: 'Este dispositivo já pertence a outro revendedor e não pode ser adicionado' });
  }

  // Vincular: atualiza os registros ativos deste MAC
  const updateFields = {
    revendedor_id:   req.resellerId,
    nome_revendedor: req.reseller.nome || req.reseller.username || null
  };
  if (comment)    updateFields.nome_cliente    = comment;
  if (deviceName) updateFields.nome_dispositivo = deviceName;
  if (modelo)     updateFields.modelo_dispositivo = modelo;

  const { error } = await supabase
    .from('ativacoes')
    .update(updateFields)
    .eq('mac_address', macKey)
    .eq('ativo', true);

  if (error) {
    console.error('Erro ao vincular dispositivo:', error.message);
    return res.status(500).json({ error: 'Erro ao vincular dispositivo' });
  }

  // Mantém cache in-memory
  const devList = resellerDevices.get(req.resellerId) || [];
  if (!devList.some(d => d.mac === macKey)) {
    devList.push({ mac: macKey, comment: comment||'', model: modelo||'', activationExpires: null, createdAt: new Date().toISOString() });
    resellerDevices.set(req.resellerId, devList);
    saveResellerState();
  }
  res.json({ success: true });
});

app.post('/api/reseller/devices/update', authReseller, async (req, res) => {
  const { mac, newMac, comment, deviceKey, deviceName, password } = req.body;
  const macKey    = (mac    || '').toLowerCase();
  const newMacKey = (newMac || '').toLowerCase();
  const changingMac = newMacKey && newMacKey !== macKey;
  const isAdmin = req.reseller?.role === 'administrador';

  if (changingMac) {
    // Admin não precisa de senha para trocar MAC; revendedor precisa
    if (!isAdmin) {
      const r = req.reseller;
      let valid = false;
      if (r.passwordHash) {
        valid = r.passwordHash === hashPassword(password || '');
      } else if (r.supabaseId) {
        const { data: rows } = await supabase.from('usuarios').select('senha_hash').eq('id', r.supabaseId).limit(1);
        if (rows?.[0]) valid = await bcrypt.compare(password || '', rows[0].senha_hash);
      }
      if (!valid) return res.status(401).json({ error: 'Senha incorreta' });
    }

    const newModel = detectModelFromMac(newMacKey) || null;
    let q = supabase.from('ativacoes').update({
      mac_address:        newMacKey,
      modelo_dispositivo: newModel,
      ...(comment    !== undefined && { nome_cliente:     comment    || null }),
      ...(deviceKey  !== undefined && { device_key:       deviceKey  || null }),
      ...(deviceName !== undefined && { nome_dispositivo: deviceName || null })
    }).eq('mac_address', macKey);
    if (!isAdmin) q = q.eq('revendedor_id', req.resellerId);
    await q;

    const devList = resellerDevices.get(req.resellerId) || [];
    const entry = devList.find(d => d.mac === macKey);
    if (entry) { entry.mac = newMacKey; entry.model = newModel; }
    saveResellerState();
  } else {
    const updateFields = {};
    if (comment    !== undefined) updateFields.nome_cliente     = comment    || null;
    if (deviceKey  !== undefined) updateFields.device_key       = deviceKey  || null;
    if (deviceName !== undefined) updateFields.nome_dispositivo = deviceName || null;

    if (Object.keys(updateFields).length) {
      let q = supabase.from('ativacoes').update(updateFields).eq('mac_address', macKey);
      if (!isAdmin) q = q.eq('revendedor_id', req.resellerId);
      await q;
    }
    const devList = resellerDevices.get(req.resellerId) || [];
    const entry = devList.find(d => d.mac === macKey);
    if (entry) { entry.comment = comment || ''; saveResellerState(); }
  }

  res.json({ success: true });
});

app.post('/api/reseller/devices/deactivate', authReseller, async (req, res) => {
  const { mac } = req.body;
  const macKey = (mac || '').toLowerCase();
  const isAdmin = req.reseller?.role === 'administrador';

  let q = supabase.from('ativacoes')
    .update({ ativo: false, atualizado_em: new Date().toISOString() })
    .eq('mac_address', macKey);
  if (!isAdmin) q = q.eq('revendedor_id', req.resellerId);
  const { data: deativRows } = await q.select('nome_cliente, revendedor_id, nome_revendedor, plano');

  if (deativRows?.length) {
    const row = deativRows[0];
    insertHistoricoAtivacao({
      mac: macKey, evento: 'desativacao',
      plano: row.plano || null,
      validadeInicio: new Date().toISOString(), validadeFim: null,
      revendedorId: req.resellerId,
      nomeRevendedor: req.reseller.nome || req.reseller.username || null,
      nomeCliente: row.nome_cliente || null,
      criadoPor: isAdmin ? 'administrador' : 'revendedor'
    }).catch(e => console.error('[historico] desativacao:', e.message));
  }

  const devList = resellerDevices.get(req.resellerId) || [];
  const entry = devList.find(d => d.mac === macKey);
  if (entry) { entry.active = false; saveResellerState(); }
  res.json({ success: true });
});

// ── LISTAS (gestão de playlists dos clientes) ────────────────────────────────
function encryptXtreamPassword(password) {
  const key = Buffer.from(process.env.PLAYLIST_SECRET, 'hex');
  const iv  = crypto.randomBytes(16);
  const cipher = crypto.createCipheriv('aes-256-cbc', key, iv);
  const enc = Buffer.concat([cipher.update(password, 'utf8'), cipher.final()]);
  return iv.toString('hex') + ':' + enc.toString('hex');
}

async function checkListaAcesso(id, resellerId, isAdmin) {
  const { data: lista } = await supabase.from('listas').select('mac_address').eq('id', id).single();
  if (!lista) return { erro: 'Lista não encontrada', status: 404 };
  if (!isAdmin) {
    const { data: atv } = await supabase.from('ativacoes').select('revendedor_id').eq('mac_address', lista.mac_address).single();
    if (atv?.revendedor_id !== resellerId) return { erro: 'Acesso negado', status: 403 };
  }
  return { lista };
}

app.get('/api/reseller/listas', authReseller, async (req, res) => {
  const isAdmin = req.reseller?.role === 'administrador';
  const { filtro, revendedor_id } = req.query;

  // Todos os devices ativos com revendedor (nunca autônomos)
  let atvQ = supabase.from('ativacoes')
    .select('mac_address, revendedor_id, nome_revendedor, modelo_dispositivo, validade, ativo, nome_cliente, nome_dispositivo')
    .not('revendedor_id', 'is', null)
    .eq('ativo', true);
  if (isAdmin) {
    if (filtro === 'revendedor' && revendedor_id) atvQ = atvQ.eq('revendedor_id', revendedor_id);
  } else {
    atvQ = atvQ.eq('revendedor_id', req.resellerId);
  }
  const { data: ativacoes, error: atvErr } = await atvQ;
  if (atvErr) return res.status(500).json({ error: atvErr.message });

  const todosAtv = ativacoes || [];
  const macs = todosAtv.map(a => a.mac_address).filter(Boolean);

  const atvMap = {};
  todosAtv.forEach(a => { if (a.mac_address) atvMap[a.mac_address] = a; });

  let listas = [];
  if (macs.length) {
    const { data: lstData, error: lstErr } = await supabase.from('listas')
      .select('id, mac_address, ativacao_id, nome_lista, tipo, url, url_epg, servidor, utilizador_xtream, nome_cliente, nome_dispositivo, device_key, protect_playlist, criado_em, expira_em, status, lista_local_id')
      .in('mac_address', macs)
      .order('criado_em', { ascending: false });
    if (!lstErr) listas = lstData || [];
  }

  const macsComLista = new Set(listas.map(l => l.mac_address));

  const enrichedListas = listas.map(l => ({
    ...l,
    modelo_dispositivo:  atvMap[l.mac_address]?.modelo_dispositivo || null,
    validade_dispositivo: atvMap[l.mac_address]?.validade || null,
    revendedor_id:       atvMap[l.mac_address]?.revendedor_id || null,
    nome_revendedor:     atvMap[l.mac_address]?.nome_revendedor || null,
    // Preenche nome_cliente/nome_dispositivo da ativação se a lista não tiver
    nome_cliente:        l.nome_cliente || atvMap[l.mac_address]?.nome_cliente || null,
    nome_dispositivo:    l.nome_dispositivo || atvMap[l.mac_address]?.nome_dispositivo || null,
  }));

  // Devices que não têm nenhuma lista cadastrada
  const dispositivosSemLista = todosAtv
    .filter(a => !macsComLista.has(a.mac_address))
    .map(a => ({
      mac_address:      a.mac_address,
      nome_cliente:     a.nome_cliente || null,
      nome_dispositivo: a.nome_dispositivo || null,
      modelo_dispositivo: a.modelo_dispositivo || null,
    }));

  res.json({ listas: enrichedListas, dispositivos_sem_lista: dispositivosSemLista });
});

app.post('/api/reseller/listas/toggle-active', authReseller, async (req, res) => {
  const { id, mac_address } = req.body;
  if (!id || !mac_address) return res.status(400).json({ error: 'id e mac_address obrigatórios' });
  const isAdmin = req.reseller?.role === 'administrador';
  const check = await checkListaAcesso(id, req.resellerId, isAdmin);
  if (check.erro) return res.status(check.status).json({ error: check.erro });

  const mac = mac_address.toLowerCase().trim();
  // Desativa todas as listas do mesmo MAC
  await supabase.from('listas').update({ status: 'inativa' }).eq('mac_address', mac);
  // Ativa a selecionada
  const { error } = await supabase.from('listas').update({ status: 'ativa' }).eq('id', id);
  if (error) return res.status(500).json({ error: error.message });
  res.json({ success: true });
});

app.post('/api/reseller/listas/renew', authReseller, async (req, res) => {
  const { id, meses, nome_lista, nome_dispositivo, device_key,
          tipo, url, url_epg, ficheiro_nome, servidor, utilizador_xtream, senha_xtream,
          protect_playlist, pin } = req.body;
  if (!id || !meses) return res.status(400).json({ error: 'id e meses obrigatórios' });
  const isAdmin = req.reseller?.role === 'administrador';
  const check = await checkListaAcesso(id, req.resellerId, isAdmin);
  if (check.erro) return res.status(check.status).json({ error: check.erro });

  const agora = new Date();
  const expira = new Date(agora);
  expira.setMonth(expira.getMonth() + parseInt(meses));

  const mac = check.lista.mac_address;
  await supabase.from('listas').update({ status: 'inativa' }).eq('mac_address', mac);

  const updateFields = { status: 'ativa', expira_em: expira.toISOString() };
  if (nome_lista)       updateFields.nome_lista        = nome_lista;
  if (nome_dispositivo) updateFields.nome_dispositivo  = nome_dispositivo;
  if (device_key)       updateFields.device_key        = device_key;
  if (tipo)             updateFields.tipo               = tipo;

  if (tipo === 'url' || tipo === 'm3u8') {
    updateFields.url               = url || null;
    updateFields.url_epg           = url_epg || null;
    updateFields.ficheiro_nome     = null;
    updateFields.servidor          = null;
    updateFields.utilizador_xtream = null;
  } else if (tipo === 'file') {
    updateFields.ficheiro_nome     = ficheiro_nome || null;
    updateFields.url_epg           = url_epg || null;
    updateFields.url               = null;
    updateFields.servidor          = null;
    updateFields.utilizador_xtream = null;
  } else if (tipo === 'xtream') {
    updateFields.servidor          = servidor || null;
    updateFields.utilizador_xtream = utilizador_xtream || null;
    updateFields.url               = null;
    updateFields.ficheiro_nome     = null;
    if (senha_xtream) updateFields.senha_xtream_enc = encryptText(senha_xtream);
  }

  if (protect_playlist !== undefined) {
    updateFields.protect_playlist = !!protect_playlist;
    if (protect_playlist && pin) {
      updateFields.pin_hash = crypto.createHash('sha256').update(pin).digest('hex');
    } else if (!protect_playlist) {
      updateFields.pin_hash = null;
    }
  }

  const { error } = await supabase.from('listas').update(updateFields).eq('id', id);
  if (error) return res.status(500).json({ error: error.message });
  res.json({ success: true, expira_em: expira.toISOString() });
});

app.post('/api/reseller/listas/add', authReseller, async (req, res) => {
  const { mac_address, nome_lista, tipo, meses, url, ficheiro_nome,
          servidor, utilizador_xtream, senha_xtream, url_epg, protect_playlist, pin } = req.body;
  if (!mac_address || !tipo) return res.status(400).json({ error: 'mac_address e tipo obrigatórios' });
  const isAdmin = req.reseller?.role === 'administrador';
  const mac = mac_address.toLowerCase().trim();

  const { data: atv } = await supabase.from('ativacoes')
    .select('revendedor_id, nome_cliente, nome_dispositivo, id, device_key')
    .eq('mac_address', mac).single();
  if (!atv) return res.status(404).json({ error: 'Dispositivo não encontrado' });
  if (!isAdmin && atv.revendedor_id !== req.resellerId) return res.status(403).json({ error: 'Acesso negado' });

  const expira = new Date();
  expira.setMonth(expira.getMonth() + (parseInt(meses) || 1));

  const { error } = await supabase.from('listas').insert({
    mac_address:       mac,
    ativacao_id:       atv.id || null,
    device_key:        atv.device_key || null,
    nome_lista:        nome_lista || null,
    tipo,
    url:               (tipo === 'url' || tipo === 'm3u8') ? (url || null) : null,
    url_epg:           url_epg || null,
    ficheiro_nome:     tipo === 'file' ? (ficheiro_nome || null) : null,
    servidor:          tipo === 'xtream' ? (servidor || null) : null,
    utilizador_xtream: tipo === 'xtream' ? (utilizador_xtream || null) : null,
    senha_xtream_enc:  (tipo === 'xtream' && senha_xtream) ? encryptText(senha_xtream) : null,
    protect_playlist:  protect_playlist ? true : false,
    pin_hash:          (protect_playlist && pin) ? crypto.createHash('sha256').update(pin).digest('hex') : null,
    nome_cliente:      atv.nome_cliente || null,
    nome_dispositivo:  atv.nome_dispositivo || null,
    expira_em:         expira.toISOString(),
    status:            'inativa',
    criado_em:         new Date().toISOString(),
  });
  if (error) return res.status(500).json({ error: error.message });
  res.json({ success: true });
});

app.post('/api/reseller/listas/deactivate', authReseller, async (req, res) => {
  const { id } = req.body;
  if (!id) return res.status(400).json({ error: 'id obrigatório' });
  const isAdmin = req.reseller?.role === 'administrador';
  const check = await checkListaAcesso(id, req.resellerId, isAdmin);
  if (check.erro) return res.status(check.status).json({ error: check.erro });
  const { error } = await supabase.from('listas').delete().eq('id', id);
  if (error) return res.status(500).json({ error: error.message });
  res.json({ success: true });
});

app.post('/api/reseller/listas/update-nome', authReseller, async (req, res) => {
  const { id, nome_lista } = req.body;
  if (!id) return res.status(400).json({ error: 'id obrigatório' });
  const isAdmin = req.reseller?.role === 'administrador';
  const check = await checkListaAcesso(id, req.resellerId, isAdmin);
  if (check.erro) return res.status(check.status).json({ error: check.erro });
  const { error } = await supabase.from('listas').update({ nome_lista: nome_lista || null }).eq('id', id);
  if (error) return res.status(500).json({ error: error.message });
  res.json({ success: true });
});

app.post('/api/reseller/listas/update-fields', authReseller, async (req, res) => {
  const { id, nome_lista, nome_dispositivo, url, url_epg, ficheiro_nome,
          servidor, utilizador_xtream, senha_xtream, protect_playlist, pin } = req.body;
  if (!id) return res.status(400).json({ error: 'id obrigatório' });
  const isAdmin = req.reseller?.role === 'administrador';
  const check = await checkListaAcesso(id, req.resellerId, isAdmin);
  if (check.erro) return res.status(check.status).json({ error: check.erro });

  const fields = {};
  if (nome_lista       !== undefined) fields.nome_lista        = nome_lista || null;
  if (nome_dispositivo !== undefined) fields.nome_dispositivo  = nome_dispositivo || null;
  if (url              !== undefined) fields.url               = url || null;
  if (url_epg          !== undefined) fields.url_epg           = url_epg || null;
  if (ficheiro_nome    !== undefined) fields.ficheiro_nome     = ficheiro_nome || null;
  if (servidor         !== undefined) fields.servidor          = servidor || null;
  if (utilizador_xtream!== undefined) fields.utilizador_xtream = utilizador_xtream || null;
  if (senha_xtream)                   fields.senha_xtream_enc  = encryptText(senha_xtream);
  if (protect_playlist !== undefined) {
    fields.protect_playlist = !!protect_playlist;
    if (protect_playlist && pin) {
      fields.pin_hash = crypto.createHash('sha256').update(pin).digest('hex');
    } else if (!protect_playlist) {
      fields.pin_hash = null;
    }
  }

  const { error } = await supabase.from('listas').update(fields).eq('id', id);
  if (error) return res.status(500).json({ error: error.message });
  res.json({ success: true });
});

app.post('/api/reseller/devices/remove', authReseller, (req, res) => {
  const { mac } = req.body;
  const macKey = (mac || '').toLowerCase();
  const devList = (resellerDevices.get(req.resellerId) || []).filter(d => d.mac !== macKey);
  resellerDevices.set(req.resellerId, devList);
  saveResellerState();
  res.json({ success: true });
});

// ── CONTROLE PARENTAL (reseller) ─────────────────────────────────────────────

// Helper: verifica se um MAC pertence ao revendedor
async function checkMacAcesso(mac, resellerId, isAdmin) {
  if (isAdmin) return { ok: true };
  const { data } = await supabase.from('ativacoes')
    .select('id').eq('mac_address', mac).eq('revendedor_id', resellerId).limit(1);
  if (!data?.length) return { erro: 'Dispositivo não encontrado', status: 403 };
  return { ok: true };
}

app.get('/api/reseller/parental', authReseller, async (req, res) => {
  const rid = req.resellerId;
  const isAdmin = req.reseller?.role === 'administrador';

  // Buscar todos os dispositivos do revendedor
  let atvQuery = supabase.from('ativacoes')
    .select('mac_address, nome_cliente, nome_dispositivo')
    .eq('ativo', true);
  if (!isAdmin) atvQuery = atvQuery.eq('revendedor_id', rid);

  const { data: atvData } = await atvQuery;
  const atvList = atvData || [];
  const macs = atvList.map(a => a.mac_address).filter(Boolean);

  if (!macs.length) return res.json([]);

  // Buscar registros parental existentes
  const { data: parentalData } = await supabase
    .from('parental')
    .select('mac_address, nome_cliente, nome_dispositivo, canais_bloqueados, pin_hash')
    .in('mac_address', macs);

  const parentalMap = {};
  (parentalData || []).forEach(p => { parentalMap[p.mac_address] = p; });

  // Retornar apenas dispositivos que têm registro parental (canais bloqueados ou PIN)
  const result = atvList
    .map(a => {
      const p = parentalMap[a.mac_address];
      if (!p) return null;
      const canais = p.canais_bloqueados || [];
      const hasPin = !!p.pin_hash;
      if (!canais.length && !hasPin) return null; // sem nada parental configurado
      return {
        mac_address:      a.mac_address,
        nome_cliente:     p.nome_cliente || a.nome_cliente || null,
        nome_dispositivo: p.nome_dispositivo || a.nome_dispositivo || null,
        canais_bloqueados: canais,
        has_pin: hasPin,
      };
    })
    .filter(Boolean);

  res.json(result);
});

app.post('/api/reseller/parental/unlock-canal', authReseller, async (req, res) => {
  const { mac, canal } = req.body;
  if (!mac || !canal) return res.status(400).json({ error: 'mac e canal obrigatórios' });
  const isAdmin = req.reseller?.role === 'administrador';
  const check = await checkMacAcesso(mac.toLowerCase(), req.resellerId, isAdmin);
  if (check.erro) return res.status(check.status).json({ error: check.erro });

  const record = await getParentalRecord(mac.toLowerCase());
  if (!record) return res.status(404).json({ error: 'Nenhuma configuração parental encontrada' });

  const novosCanais = (record.canais_bloqueados || []).filter(c => c !== canal);
  const err = await upsertParental(mac.toLowerCase(), { canais_bloqueados: novosCanais });
  if (err) return res.status(500).json({ error: err.message });
  res.json({ success: true, canais_bloqueados: novosCanais });
});

app.post('/api/reseller/parental/set-pin', authReseller, async (req, res) => {
  const { mac, newPin } = req.body;
  if (!mac || !newPin || newPin.length < 4)
    return res.status(400).json({ error: 'Senha deve ter pelo menos 4 dígitos' });
  const isAdmin = req.reseller?.role === 'administrador';
  const check = await checkMacAcesso(mac.toLowerCase(), req.resellerId, isAdmin);
  if (check.erro) return res.status(check.status).json({ error: check.erro });

  const err = await upsertParental(mac.toLowerCase(), { pin_hash: hashPin(newPin) });
  if (err) return res.status(500).json({ error: err.message });
  res.json({ success: true });
});

// ── SALDO (ledger) ────────────────────────────────────────────────────────────
async function getSaldoAtual(revendedorId) {
  const { data } = await supabase
    .from('saldo')
    .select('saldo_resultante')
    .eq('revendedor_id', revendedorId)
    .order('criado_em', { ascending: false })
    .limit(1);
  return data?.[0]?.saldo_resultante ?? null;
}

async function insertSaldo({ revendedorId, nomeRevendedor, tipo, quantidade, referenciaId = null, descricao = null }) {
  const saldoAtual = (await getSaldoAtual(revendedorId)) ?? 0;
  const saldoResultante = saldoAtual + quantidade;

  const { error } = await supabase.from('saldo').insert({
    revendedor_id:    revendedorId,
    nome_revendedor:  nomeRevendedor || null,
    tipo,
    quantidade,
    saldo_resultante: saldoResultante,
    referencia_id:    referenciaId,
    descricao,
    criado_em:        new Date().toISOString()
  });

  if (error) console.error('Erro ao inserir saldo:', error.message);
  return saldoResultante;
}

// ── TRANSACOES ────────────────────────────────────────────────────────────────
async function insertTransacao({ pagamentoId, origem, data, status = 'pago', tipoPagamento, identificador, nome, valorPago, revendedorId }) {
  const { error } = await supabase.from('transacoes').upsert({
    pagamento_id:   pagamentoId,
    origem,
    data:           data || new Date().toISOString(),
    status,
    tipo_pagamento: tipoPagamento || null,
    identificador:  String(identificador || ''),
    nome:           nome || null,
    valor_pago:     parseFloat(valorPago) || 0,
    revendedor_id:  revendedorId || null,
    criado_em:      new Date().toISOString()
  }, { onConflict: 'pagamento_id', ignoreDuplicates: true });
  if (error && error.code !== '23505') console.error('[transacoes] Erro:', error.message);
}

async function insertHistoricoAtivacao({
  mac, evento, plano, validadeInicio, validadeFim,
  revendedorId, nomeRevendedor, nomeCliente, deviceKey,
  pagamentoId, tipoPagamento, valorPago, criadoPor, observacao
}) {
  const { error } = await supabase.from('historico_ativacoes').insert({
    mac_address:     mac,
    evento,
    plano:           plano || null,
    validade_inicio: validadeInicio || new Date().toISOString(),
    validade_fim:    validadeFim    || null,
    revendedor_id:   revendedorId   || null,
    nome_revendedor: nomeRevendedor || null,
    nome_cliente:    nomeCliente    || null,
    device_key:      deviceKey      || null,
    pagamento_id:    pagamentoId    || null,
    tipo_pagamento:  tipoPagamento  || null,
    valor_pago:      parseFloat(valorPago) || 0,
    criado_por:      criadoPor      || 'sistema',
    observacao:      observacao     || null,
    criado_em:       new Date().toISOString()
  });
  if (error) console.error('[historico_ativacoes] Erro:', error.message);
}

// ── CREDITS / BUY ─────────────────────────────────────────────────────────────
async function saveCreditoPurchase({ resellerId, nomeRevendedor, tipoPagamento, transacaoId, quantidade, valorPago }) {
  const valorReais = parseFloat((valorPago / 100).toFixed(2));
  const agora = new Date().toISOString();

  const { error } = await supabase.from('creditos').insert({
    tipo_pagamento:  tipoPagamento,
    transacao_id:    transacaoId || null,
    quantidade,
    valor_pago:      valorReais,
    data_compra:     agora,
    status:          'pago',
    revendedor_id:   resellerId,
    nome_revendedor: nomeRevendedor || null
  });
  if (error) console.error('Erro ao gravar credito:', error.message);

  // Registrar na tabela unificada de transações
  insertTransacao({
    pagamentoId:   transacaoId || ('cred_' + resellerId + '_' + Date.now()),
    origem:        'credito',
    data:          agora,
    status:        'pago',
    tipoPagamento,
    identificador: resellerId,
    nome:          nomeRevendedor,
    valorPago:     valorReais,
    revendedorId:  resellerId
  }).catch(e => console.error('[transacoes] credito:', e.message));

  // Registrar entrada no saldo
  await insertSaldo({
    revendedorId:   resellerId,
    nomeRevendedor,
    tipo:           'compra',
    quantidade,
    referenciaId:   transacaoId || null,
    descricao:      `Compra de ${quantidade} crédito(s) via ${tipoPagamento}`
  });
}

app.post('/api/reseller/buy-credits', authReseller, async (req, res) => {
  const { packageId, method } = req.body;
  let pkg;
  const { data: dbPkg } = await supabase
    .from('pacotes_credito')
    .select('id, nome, ativacoes, valor_centavos')
    .eq('id', packageId).eq('ativo', true).single();
  if (dbPkg) {
    pkg = { id: dbPkg.id, name: dbPkg.nome, activations: dbPkg.ativacoes, priceInCents: dbPkg.valor_centavos };
  } else {
    pkg = CREDIT_PACKAGES.find(p => p.id === packageId); // fallback
  }
  if (!pkg) return res.status(400).json({ error: 'Pacote inválido' });

  const txnId = genId();
  const txns = resellerTxns.get(req.resellerId) || [];
  const nomeRevendedor = req.reseller.nome || req.reseller.username || null;

  if (MOCK_PAYMENTS || method === 'mock') {
    const txn = { id: txnId, method: 'mock', transactionId: 'mock_'+txnId, creditCount: pkg.activations, status: 'paid', priceInCents: pkg.priceInCents, createdAt: new Date().toISOString() };
    txns.unshift(txn);
    resellerTxns.set(req.resellerId, txns);
    req.reseller.credits += pkg.activations;
    saveResellerState();
    saveCreditoPurchase({
      resellerId: req.resellerId, nomeRevendedor,
      tipoPagamento: 'mock', transacaoId: txn.transactionId,
      quantidade: pkg.activations, valorPago: pkg.priceInCents
    }).catch(e => console.error(e));
    return res.json({ paymentId: txnId, status: 'paid' });
  }

  const txn = { id: txnId, method: method||'pix', transactionId: null, creditCount: pkg.activations, status: 'pending', priceInCents: pkg.priceInCents, createdAt: new Date().toISOString() };
  txns.unshift(txn);
  resellerTxns.set(req.resellerId, txns);

  const fakeCode = `00020126580014BR.GOV.BCB.PIX0136reseller-${txnId}5204000053039865406${(pkg.priceInCents/100).toFixed(2)}5802BR5919Alpha Prime6009SAO PAULO62070503***6304ABCD`;
  saveResellerState();

  setTimeout(() => {
    txn.status = 'paid';
    txn.transactionId = 'auto_' + txnId;
    req.reseller.credits += pkg.activations;
    saveResellerState();
    saveCreditoPurchase({
      resellerId: req.resellerId, nomeRevendedor,
      tipoPagamento: method || 'pix', transacaoId: txn.transactionId,
      quantidade: pkg.activations, valorPago: pkg.priceInCents
    }).catch(e => console.error(e));
  }, MOCK_AUTO_CONFIRM_MS);

  res.json({
    paymentId: txnId,
    qrCodeBase64: `https://api.qrserver.com/v1/create-qr-code/?size=220x220&data=${encodeURIComponent(fakeCode)}`,
    qrCodeText: fakeCode
  });
});

app.get('/api/reseller/buy-credits/status', authReseller, (req, res) => {
  const txns = resellerTxns.get(req.resellerId) || [];
  const txn = txns.find(t => t.id === req.query.id);
  if (!txn) return res.status(404).json({ error: 'Transação não encontrada' });
  res.json({ status: txn.status });
});

app.get('/api/reseller/transactions', authReseller, async (req, res) => {
  const { data, error } = await supabase
    .from('saldo')
    .select('id, tipo, quantidade, descricao, saldo_resultante, referencia_id, criado_em')
    .eq('revendedor_id', req.resellerId)
    .eq('tipo', 'compra')
    .order('criado_em', { ascending: false });
  if (error) return res.status(500).json({ error: error.message });
  const rows = (data || []).map(r => ({
    ...r,
    saldo_inicial: r.saldo_resultante - r.quantidade,
    saldo_final:   r.saldo_resultante
  }));
  res.json(rows);
});

app.get('/api/reseller/activations-log', authReseller, async (req, res) => {
  const { data, error } = await supabase
    .from('saldo')
    .select('id, tipo, quantidade, descricao, saldo_resultante, referencia_id, criado_em')
    .eq('revendedor_id', req.resellerId)
    .eq('tipo', 'uso')
    .order('criado_em', { ascending: false });
  if (error) return res.status(500).json({ error: error.message });

  // Buscar nome_cliente em ativacoes pelos MACs únicos
  const macs = [...new Set((data || []).map(r => r.referencia_id).filter(Boolean))];
  let nomeMap = {};
  if (macs.length) {
    const { data: ativRows } = await supabase
      .from('ativacoes')
      .select('mac_address, nome_cliente')
      .in('mac_address', macs);
    (ativRows || []).forEach(a => { nomeMap[a.mac_address] = a.nome_cliente; });
  }

  const rows = (data || []).map(r => ({
    ...r,
    mac_address:   r.referencia_id || null,
    nome_cliente:  nomeMap[r.referencia_id] || null,
    saldo_inicial: r.saldo_resultante - r.quantidade,
    saldo_final:   r.saldo_resultante
  }));
  res.json(rows);
});

// Créditos gerais — somente administrador: saldo atual de cada revendedor
app.get('/api/reseller/credits/geral', authReseller, async (req, res) => {
  if (req.reseller?.role !== 'administrador') return res.status(403).json({ error: 'Acesso negado' });

  const { data: usuarios, error } = await supabase
    .from('usuarios')
    .select('id, nome, email, role')
    .in('role', ['revendedor', 'administrador'])
    .eq('ativo', true)
    .order('nome', { ascending: true });

  if (error) return res.status(500).json({ error: error.message });

  // Busca o saldo mais recente de cada revendedor em paralelo
  const results = await Promise.all((usuarios || []).map(async u => {
    const { data: s } = await supabase
      .from('saldo')
      .select('saldo_resultante, criado_em')
      .eq('revendedor_id', u.id)
      .order('criado_em', { ascending: false })
      .limit(1);
    return {
      id: u.id,
      nome: u.nome || u.email,
      email: u.email,
      role: u.role,
      saldo: s?.[0]?.saldo_resultante ?? 0,
      ultimo_movimento: s?.[0]?.criado_em || null
    };
  }));

  res.json(results);
});

// ── REFERRAL LINKS ────────────────────────────────────────────────────────────
app.get('/api/reseller/referral-links', authReseller, async (req, res) => {
  const { data, error } = await supabase
    .from('links_indicacao')
    .select('id, nome, codigo, plano, ativacoes, ativo, criado_em')
    .eq('revendedor_id', req.resellerId)
    .eq('ativo', true)
    .order('criado_em', { ascending: false });
  if (error) return res.status(500).json({ error: error.message });
  // normalizar campos para o frontend
  res.json((data || []).map(l => ({
    id: l.id, name: l.nome, code: l.codigo,
    plan: l.plano, activations: l.ativacoes || 0, createdAt: l.criado_em
  })));
});

app.post('/api/reseller/referral-links', authReseller, async (req, res) => {
  const { name, plan } = req.body;
  if (!name) return res.status(400).json({ error: 'Nome obrigatório' });
  const id   = genId();
  const code = genCode();
  const { data, error } = await supabase.from('links_indicacao').insert({
    id, revendedor_id: req.resellerId, nome: name,
    codigo: code, plano: plan || 'yearly', ativacoes: 0,
    ativo: true, criado_em: new Date().toISOString()
  }).select().single();
  if (error) return res.status(500).json({ error: error.message });
  res.json({ id, name, code, plan: plan || 'yearly', activations: 0, createdAt: data.criado_em });
});

app.delete('/api/reseller/referral-links/:id', authReseller, async (req, res) => {
  const { error } = await supabase
    .from('links_indicacao')
    .update({ ativo: false })
    .eq('id', req.params.id)
    .eq('revendedor_id', req.resellerId);
  if (error) return res.status(500).json({ error: error.message });
  res.json({ success: true });
});

// ── FATURAMENTO ───────────────────────────────────────────────────────────────
const PLAN_PRICE_MAP = { anual: 25.00, vitalicio: 65.00, mensal: 5.00, trimestral: 12.00, semestral: 18.00 };

function mesRange(mes) {
  const inicio = new Date(mes + '-01T00:00:00.000Z').toISOString();
  const fimD = new Date(mes + '-01'); fimD.setMonth(fimD.getMonth() + 1);
  return { inicio, fim: fimD.toISOString() };
}

async function fetchFatTransacoes(mes, filtro, revId) {
  const { inicio, fim } = mesRange(mes);
  let q = supabase.from('transacoes')
    .select('pagamento_id, origem, valor_pago, revendedor_id, data, status, tipo_pagamento, identificador, nome, criado_em')
    .eq('status', 'pago')
    .gte('data', inicio).lt('data', fim);
  if (filtro === 'autonomo')                 q = q.is('revendedor_id', null);
  else if (filtro === 'revendedor' && revId) q = q.eq('revendedor_id', revId);
  const { data, error } = await q;

  // Fallback: se tabela transacoes não existe ainda, usa ativacoes + creditos
  if (error) {
    console.warn('[faturamento] tabela transacoes indisponível — usando fallback');
    const [ativRows, credRows] = await Promise.all([
      (async () => {
        let qa = supabase.from('ativacoes').select('plano, revendedor_id, criado_em, mac_address, nome_cliente')
          .neq('pagamento_id', 'CREDITO REVENDA').not('pagamento_id', 'is', null)
          .gte('criado_em', inicio).lt('criado_em', fim);
        if (filtro === 'autonomo')                 qa = qa.is('revendedor_id', null);
        else if (filtro === 'revendedor' && revId) qa = qa.eq('revendedor_id', revId);
        const { data: d } = await qa;
        return (d || []).map(r => ({
          origem: 'ativacao', valor_pago: PLAN_PRICE_MAP[r.plano] || 0,
          revendedor_id: r.revendedor_id, data: r.criado_em, status: 'pago',
          identificador: r.mac_address, nome: r.nome_cliente
        }));
      })(),
      (async () => {
        let qc = supabase.from('creditos').select('valor_pago, revendedor_id, data_compra, nome_revendedor, tipo_pagamento')
          .eq('status', 'pago').gte('data_compra', inicio).lt('data_compra', fim);
        if (filtro === 'autonomo')                 qc = qc.is('revendedor_id', null);
        else if (filtro === 'revendedor' && revId) qc = qc.eq('revendedor_id', revId);
        const { data: d } = await qc;
        return (d || []).map(r => ({
          origem: 'credito', valor_pago: parseFloat(r.valor_pago) || 0,
          revendedor_id: r.revendedor_id, data: r.data_compra, status: 'pago',
          identificador: r.revendedor_id, nome: r.nome_revendedor
        }));
      })()
    ]);
    return [...ativRows, ...credRows];
  }
  return data || [];
}

app.get('/api/reseller/faturamento/resumo', authReseller, async (req, res) => {
  const mes    = req.query.mes    || new Date().toISOString().slice(0, 7);
  const filtro = req.query.filtro || 'geral';
  const revId  = req.query.revendedor_id || null;

  const rows = await fetchFatTransacoes(mes, filtro, revId);
  const ativRows = rows.filter(r => r.origem === 'ativacao');
  const credRows = rows.filter(r => r.origem === 'credito');
  const totalAtiv = ativRows.reduce((s, r) => s + (parseFloat(r.valor_pago) || 0), 0);
  const totalCred = credRows.reduce((s, r) => s + (parseFloat(r.valor_pago) || 0), 0);

  res.json({
    mes,
    ativacoes: { valor: totalAtiv, quantidade: ativRows.length },
    creditos:  { valor: totalCred, quantidade: credRows.length },
    total: totalAtiv + totalCred
  });
});

app.get('/api/reseller/faturamento/diario', authReseller, async (req, res) => {
  const mes    = req.query.mes    || new Date().toISOString().slice(0, 7);
  const filtro = req.query.filtro || 'geral';
  const revId  = req.query.revendedor_id || null;

  const rows = await fetchFatTransacoes(mes, filtro, revId);
  const days = {};
  rows.forEach(r => {
    const d = (r.data || '').slice(0, 10); if (!d) return;
    if (!days[d]) days[d] = { dia: d, ativacoes: 0, creditos: 0, total: 0 };
    const v = parseFloat(r.valor_pago) || 0;
    if (r.origem === 'ativacao') { days[d].ativacoes += v; days[d].total += v; }
    else                         { days[d].creditos  += v; days[d].total += v; }
  });
  res.json(Object.values(days).sort((a, b) => a.dia.localeCompare(b.dia)));
});

// Busca direta sem depender da tabela transacoes — usada no histórico e no chart
async function fetchFatDireto(inicio, fim, filtro, revId) {
  const aplicarFiltro = (q, campo) => {
    if (filtro === 'autonomo')                 return q.is(campo, null);
    if (filtro === 'revendedor' && revId)       return q.eq(campo, revId);
    return q;
  };

  let qAtiv = supabase.from('ativacoes')
    .select('plano, revendedor_id, criado_em')
    .neq('pagamento_id', 'CREDITO REVENDA').not('pagamento_id', 'is', null)
    .gte('criado_em', inicio).lt('criado_em', fim);
  qAtiv = aplicarFiltro(qAtiv, 'revendedor_id');

  let qCred = supabase.from('creditos')
    .select('valor_pago, revendedor_id, data_compra')
    .eq('status', 'pago')
    .gte('data_compra', inicio).lt('data_compra', fim);
  qCred = aplicarFiltro(qCred, 'revendedor_id');

  const [{ data: ativ }, { data: cred }] = await Promise.all([qAtiv, qCred]);
  const atv = (ativ || []).reduce((s, r) => s + (PLAN_PRICE_MAP[r.plano] || 0), 0);
  const crd = (cred || []).reduce((s, r) => s + (parseFloat(r.valor_pago)  || 0), 0);
  return { ativacoes: atv, creditos: crd, qtdAtiv: (ativ||[]).length, qtdCred: (cred||[]).length };
}

app.get('/api/reseller/faturamento/historico', authReseller, async (req, res) => {
  const now = new Date();
  const months = Array.from({ length: 12 }, (_, i) => {
    const d = new Date(now.getFullYear(), now.getMonth() - (11 - i), 1);
    return d.toISOString().slice(0, 7);
  });

  const results = await Promise.all(months.map(async mes => {
    const { inicio, fim } = mesRange(mes);

    // Tenta transacoes primeiro; se vazio, usa direto
    const { data: txData, error: txErr } = await supabase
      .from('transacoes').select('origem, valor_pago')
      .eq('status', 'pago').gte('data', inicio).lt('data', fim);

    let atv = 0, crd = 0;
    if (!txErr && txData?.length) {
      atv = txData.filter(r => r.origem === 'ativacao').reduce((s, r) => s + (parseFloat(r.valor_pago) || 0), 0);
      crd = txData.filter(r => r.origem === 'credito' ).reduce((s, r) => s + (parseFloat(r.valor_pago) || 0), 0);
    } else {
      const d = await fetchFatDireto(inicio, fim, 'geral', null);
      atv = d.ativacoes; crd = d.creditos;
    }
    return { mes, ativacoes: atv, creditos: crd, total: atv + crd };
  }));
  res.json(results);
});

// Gráfico do mês atual — lê direto de transacoes sem filtro de status
app.get('/api/reseller/faturamento/grafico-mes', authReseller, async (req, res) => {
  const mes = new Date().toISOString().slice(0, 7);
  const { inicio, fim } = mesRange(mes);

  const { data, error } = await supabase
    .from('transacoes')
    .select('origem, valor_pago')
    .gte('data', inicio).lt('data', fim);

  if (error) {
    // Fallback: busca diretamente nas tabelas de origem
    const d = await fetchFatDireto(inicio, fim, 'geral', null);
    return res.json({ mes, ativacao: d.ativacoes, credito: d.creditos, total: d.ativacoes + d.creditos, fonte: 'direto' });
  }

  const rows = data || [];
  const ativacao = rows.filter(r => r.origem === 'ativacao').reduce((s, r) => s + (parseFloat(r.valor_pago) || 0), 0);
  const credito  = rows.filter(r => r.origem === 'credito' ).reduce((s, r) => s + (parseFloat(r.valor_pago) || 0), 0);
  res.json({ mes, ativacao, credito, total: ativacao + credito, fonte: 'transacoes', registros: rows.length });
});

app.get('/api/reseller/faturamento/transacoes', authReseller, async (req, res) => {
  const mes    = req.query.mes    || new Date().toISOString().slice(0, 7);
  const filtro = req.query.filtro || 'geral';
  const revId  = req.query.revendedor_id || null;
  const { inicio, fim } = mesRange(mes);

  let q = supabase.from('transacoes')
    .select('pagamento_id, origem, data, status, tipo_pagamento, identificador, nome, valor_pago, revendedor_id, criado_em')
    .gte('data', inicio).lt('data', fim)
    .order('data', { ascending: false });
  if (filtro === 'autonomo')                 q = q.is('revendedor_id', null);
  else if (filtro === 'revendedor' && revId) q = q.eq('revendedor_id', revId);

  const { data, error } = await q;
  if (error) return res.status(500).json({ error: error.message });

  // Resolver nomes dos revendedores
  const rows = data || [];
  const revIds = [...new Set(rows.map(r => r.revendedor_id).filter(Boolean))];
  let nomesMap = {};
  if (revIds.length) {
    const { data: users } = await supabase
      .from('usuarios').select('id, nome, email').in('id', revIds);
    (users || []).forEach(u => { nomesMap[u.id] = u.nome || u.email; });
  }

  res.json(rows.map(r => ({
    ...r,
    nome_revendedor: r.revendedor_id ? (nomesMap[r.revendedor_id] || r.revendedor_id) : '— (App direto)'
  })));
});

app.get('/api/reseller/faturamento/por-revendedor', authReseller, async (req, res) => {
  const mes = req.query.mes || new Date().toISOString().slice(0, 7);
  const { inicio, fim } = mesRange(mes);

  // Tenta tabela transacoes primeiro
  const { data: txRows, error: txErr } = await supabase
    .from('transacoes')
    .select('revendedor_id, valor_pago')
    .eq('origem', 'ativacao').eq('status', 'pago')
    .gte('data', inicio).lt('data', fim);

  let rows;
  if (txErr) {
    // Fallback: ativacoes direto
    const { data: atRows } = await supabase
      .from('ativacoes')
      .select('revendedor_id, plano')
      .neq('pagamento_id', 'CREDITO REVENDA').not('pagamento_id', 'is', null)
      .gte('criado_em', inicio).lt('criado_em', fim);
    rows = (atRows || []).map(r => ({ revendedor_id: r.revendedor_id, valor_pago: PLAN_PRICE_MAP[r.plano] || 0 }));
  } else {
    rows = txRows || [];
  }

  // Agregar por revendedor_id
  const agg = {};
  rows.forEach(r => {
    const id = r.revendedor_id || '__autonomo__';
    if (!agg[id]) agg[id] = { id, ativacoes: 0, valor: 0 };
    agg[id].ativacoes += 1;
    agg[id].valor += parseFloat(r.valor_pago) || 0;
  });

  // Buscar nomes dos revendedores
  const ids = Object.keys(agg).filter(id => id !== '__autonomo__');
  let nomesMap = {};
  if (ids.length) {
    const { data: users } = await supabase.from('usuarios').select('id, nome, email').in('id', ids);
    (users || []).forEach(u => { nomesMap[u.id] = u.nome || u.email; });
  }

  const result = Object.values(agg)
    .map(a => ({
      nome:      a.id === '__autonomo__' ? 'App (sem revendedor)' : (nomesMap[a.id] || a.id),
      ativacoes: a.ativacoes,
      valor:     parseFloat(a.valor.toFixed(2))
    }))
    .sort((a, b) => b.ativacoes - a.ativacoes);

  res.json(result);
});

app.get('/api/reseller/faturamento/revendedores', authReseller, async (req, res) => {
  const { data } = await supabase.from('usuarios').select('id, nome, email, role')
    .in('role', ['revendedor', 'administrador']).order('nome');
  res.json((data || []).map(u => ({ id: u.id, nome: (u.nome || u.email) + (u.role === 'administrador' ? ' (admin)' : ''), email: u.email })));
});

// Fechamentos persistidos em memória (pode migrar para Supabase depois)
const fechamentosMap = new Map();
app.get('/api/reseller/faturamento/fechamento', authReseller, (req, res) => {
  const mes = req.query.mes || new Date().toISOString().slice(0, 7);
  res.json(fechamentosMap.get(mes) || null);
});
app.post('/api/reseller/faturamento/fechamento', authReseller, (req, res) => {
  const { mes, taxaGateway, taxaApp, taxaSite, reembolsos, notas } = req.body;
  if (!mes) return res.status(400).json({ error: 'Mês obrigatório' });
  fechamentosMap.set(mes, { mes, taxaGateway: +taxaGateway||0, taxaApp: +taxaApp||0, taxaSite: +taxaSite||0, reembolsos: +reembolsos||0, notas: notas||'', savedAt: new Date().toISOString() });
  res.json({ success: true });
});

// ── WITHDRAWALS ───────────────────────────────────────────────────────────────
app.get('/api/reseller/withdrawals', authReseller, (req, res) => {
  res.json(resellerWithdrawals.get(req.resellerId) || []);
});

app.post('/api/reseller/withdrawals', authReseller, (req, res) => {
  const { amount, method, key } = req.body;
  if (!amount || amount < 50) return res.status(400).json({ error: 'Valor mínimo R$ 50,00' });
  const w = { id: genId(), amount, method: method||'pix', key: key||'', status: 'pending', createdAt: new Date().toISOString(), processedAt: null };
  const list = resellerWithdrawals.get(req.resellerId) || [];
  list.unshift(w);
  resellerWithdrawals.set(req.resellerId, list);
  saveResellerState();
  res.json(w);
});

// ── SUB-RESELLERS ──────────────────────────────────────────────────────────────
app.get('/api/reseller/sub-resellers', authReseller, async (req, res) => {
  if (req.reseller?.role !== 'administrador') return res.status(403).json({ error: 'Acesso negado' });

  // Busca todos revendedores no Supabase (ativos e pendentes)
  const { data: revs, error } = await supabase
    .from('usuarios')
    .select('id, nome, email, ativo, criado_em, role')
    .in('role', ['revendedor', 'administrador'])
    .order('criado_em', { ascending: false });
  if (error) return res.status(500).json({ error: error.message });

  const revIds = (revs || []).map(r => r.id);
  if (!revIds.length) return res.json([]);

  const now = new Date();
  const startOfMonth = new Date(now.getFullYear(), now.getMonth(), 1).toISOString();

  // Saldo: última linha por revendedor (mais recente = saldo atual)
  const { data: saldoRows } = await supabase
    .from('saldo')
    .select('revendedor_id, saldo_resultante, criado_em')
    .in('revendedor_id', revIds)
    .order('criado_em', { ascending: false });

  const saldoMap = {};
  (saldoRows || []).forEach(s => {
    if (!(s.revendedor_id in saldoMap)) saldoMap[s.revendedor_id] = s.saldo_resultante;
  });

  // Ativações: total e mês por revendedor
  const { data: ativRows } = await supabase
    .from('ativacoes')
    .select('revendedor_id, criado_em')
    .in('revendedor_id', revIds);

  const ativTotal = {}, ativMes = {};
  (ativRows || []).forEach(a => {
    ativTotal[a.revendedor_id] = (ativTotal[a.revendedor_id] || 0) + 1;
    if (a.criado_em >= startOfMonth)
      ativMes[a.revendedor_id] = (ativMes[a.revendedor_id] || 0) + 1;
  });

  const result = (revs || []).map(r => ({
    id:               r.id,
    nome:             r.nome || r.email,
    email:            r.email,
    credits:          saldoMap[r.id] ?? 0,
    active:           r.ativo,
    createdAt:        r.criado_em,
    totalActivations: ativTotal[r.id] || 0,
    monthActivations: ativMes[r.id]   || 0,
  }));

  res.json(result);
});

app.post('/api/reseller/sub-resellers', authReseller, async (req, res) => {
  const { nome, email, senha, telefone, pais, data_nascimento, credits } = req.body;
  if (!nome || !email || !senha) return res.status(400).json({ error: 'Nome, email e senha são obrigatórios' });
  if (senha.length < 6) return res.status(400).json({ error: 'Senha mínima 6 caracteres' });

  try {
    const senhaHash = await bcrypt.hash(senha, 12);
    const { data: inserted, error } = await supabase.from('usuarios').insert({
      nome, email: email.toLowerCase().trim(), senha_hash: senhaHash,
      telefone: telefone || null, pais: pais || null,
      data_nascimento: data_nascimento || null,
      role: 'revendedor', ativo: true, criado_em: new Date().toISOString()
    }).select('id, nome').single();

    if (error) {
      if (error.code === '23505') return res.status(409).json({ error: 'Email já cadastrado' });
      throw error;
    }

    const newId = inserted.id;
    const nomeRev = inserted.nome;
    const qtd = Math.max(0, parseInt(credits) || 0);

    if (qtd > 0) {
      // Registrar em creditos
      await supabase.from('creditos').insert({
        tipo_pagamento: 'PROMO CADASTRO',
        transacao_id:   'promo_' + newId,
        quantidade:     qtd,
        valor_pago:     0,
        data_compra:    new Date().toISOString(),
        status:         null,
        revendedor_id:  newId,
        nome_revendedor: nomeRev
      });
      // Registrar no saldo
      await insertSaldo({
        revendedorId:   newId,
        nomeRevendedor: nomeRev,
        tipo:           'PROMO CADASTRO',
        quantidade:     qtd,
        referenciaId:   'promo_cadastro',
        descricao:      'Oferta de cadastro ofertada pelo Admin'
      });
    }

    res.json({ success: true, id: newId });
  } catch (e) {
    console.error('Erro criar revendedor:', e.message);
    res.status(500).json({ error: 'Erro ao criar revendedor' });
  }
});

app.post('/api/reseller/sub-resellers/approve', authReseller, async (req, res) => {
  const { id } = req.body;
  if (!id) return res.status(400).json({ error: 'ID obrigatório' });
  const { error } = await supabase.from('usuarios').update({ ativo: true }).eq('id', id).eq('role', 'revendedor');
  if (error) return res.status(500).json({ error: error.message });
  res.json({ success: true });
});

app.post('/api/reseller/sub-resellers/deactivate-user', authReseller, async (req, res) => {
  const { id } = req.body;
  if (!id) return res.status(400).json({ error: 'ID obrigatório' });
  const { error } = await supabase.from('usuarios').update({ ativo: false }).eq('id', id).eq('role', 'revendedor');
  if (error) return res.status(500).json({ error: error.message });
  res.json({ success: true });
});

app.post('/api/reseller/sub-resellers/give-credits', authReseller, (req, res) => {
  const { subresellerId, credits } = req.body;
  const n = parseInt(credits) || 0;
  if (n <= 0) return res.status(400).json({ error: 'Quantidade inválida' });
  if (req.reseller.credits < n) return res.status(400).json({ error: 'Créditos insuficientes' });
  const sub = resellers.get(subresellerId);
  if (!sub || sub.parentId !== req.resellerId) return res.status(404).json({ error: 'Revendedor não encontrado' });
  req.reseller.credits -= n;
  sub.credits += n;
  saveResellerState();
  res.json({ success: true });
});

// ── PROFILE ───────────────────────────────────────────────────────────────────
app.get('/api/reseller/profile', authReseller, async (req, res) => {
  const { data, error } = await supabase
    .from('usuarios')
    .select('nome, email, telefone, pais, data_nascimento, criado_em')
    .eq('id', req.resellerId)
    .single();
  if (error) return res.status(500).json({ error: error.message });
  res.json(data || {});
});

app.post('/api/reseller/profile', authReseller, async (req, res) => {
  const { nome, telefone, pais, data_nascimento, email, senhaAtual } = req.body;
  const updates = {};
  if (nome            !== undefined) updates.nome            = nome?.trim()            || null;
  if (telefone        !== undefined) updates.telefone        = telefone?.trim()        || null;
  if (pais            !== undefined) updates.pais            = pais?.trim()            || null;
  if (data_nascimento !== undefined) updates.data_nascimento = data_nascimento         || null;

  if (email) {
    if (!senhaAtual) return res.status(400).json({ error: 'Senha necessária para alterar o e-mail' });
    const { data: user } = await supabase.from('usuarios').select('senha_hash').eq('id', req.resellerId).single();
    if (!user) return res.status(404).json({ error: 'Usuário não encontrado' });
    const ok = await bcrypt.compare(senhaAtual, user.senha_hash || '');
    if (!ok) return res.status(401).json({ error: 'Senha incorreta' });
    updates.email = email.toLowerCase().trim();
  }

  if (!Object.keys(updates).length) return res.json({ success: true });

  const { error } = await supabase.from('usuarios').update(updates).eq('id', req.resellerId);
  if (error) {
    if (error.code === '23505') return res.status(409).json({ error: 'E-mail já está em uso' });
    return res.status(500).json({ error: error.message });
  }
  if (updates.nome)  req.reseller.nome     = updates.nome;
  if (updates.email) req.reseller.username = updates.email;
  res.json({ success: true });
});

app.post('/api/reseller/profile/change-password', authReseller, async (req, res) => {
  const { senhaAtual, novaSenha } = req.body;
  if (!senhaAtual || !novaSenha) return res.status(400).json({ error: 'Campos obrigatórios' });
  if (novaSenha.length < 6)      return res.status(400).json({ error: 'Nova senha mínima de 6 caracteres' });
  const { data: user } = await supabase.from('usuarios').select('senha_hash').eq('id', req.resellerId).single();
  if (!user) return res.status(404).json({ error: 'Usuário não encontrado' });
  const ok = await bcrypt.compare(senhaAtual, user.senha_hash || '');
  if (!ok) return res.status(401).json({ error: 'Senha atual incorreta' });
  const newHash = await bcrypt.hash(novaSenha, 12);
  const { error } = await supabase.from('usuarios').update({ senha_hash: newHash }).eq('id', req.resellerId);
  if (error) return res.status(500).json({ error: error.message });
  res.json({ success: true });
});

app.post('/api/reseller/profile/delete-account', authReseller, async (req, res) => {
  const { senha } = req.body;
  if (!senha) return res.status(400).json({ error: 'Senha necessária para excluir conta' });
  const { data: user } = await supabase.from('usuarios').select('senha_hash').eq('id', req.resellerId).single();
  if (!user) return res.status(404).json({ error: 'Usuário não encontrado' });
  const ok = await bcrypt.compare(senha, user.senha_hash || '');
  if (!ok) return res.status(401).json({ error: 'Senha incorreta' });
  const { error } = await supabase.from('usuarios').update({
    ativo: false,
    data_autoexclusao: new Date().toISOString()
  }).eq('id', req.resellerId);
  if (error) return res.status(500).json({ error: error.message });
  // Invalidar sessão imediatamente
  const token = req.headers['x-reseller-token'];
  resellerTokens.delete(token);
  resellers.delete(req.resellerId);
  res.json({ success: true });
});

// ── REFERRAL ACTIVATION PAGE ───────────────────────────────────────────────────
app.get('/activate', (req, res) => {
  res.sendFile(path.join(__dirname, 'activate.html'));
});

// ── AUTH UTILIZADORES (Supabase) ───────────────────────────────────────────────
app.post('/api/auth/register', async (req, res) => {
  const { nome, email, senha, email_secundario, pais, telefone, data_nascimento } = req.body;
  if (!nome || !email || !senha)
    return res.status(400).json({ error: 'Nome, email e senha são obrigatórios' });
  if (senha.length < 6)
    return res.status(400).json({ error: 'A senha deve ter pelo menos 6 caracteres' });

  try {
    const senhaHash = await bcrypt.hash(senha, 12);
    const { data, error } = await supabase
      .from('usuarios')
      .insert([{
        nome,
        email: email.toLowerCase().trim(),
        senha_hash: senhaHash,
        email_secundario: email_secundario || null,
        pais: pais || null,
        telefone: telefone || null,
        data_nascimento: data_nascimento || null,
        role: 'cliente'
      }])
      .select('id, nome, email, role')
      .single();

    if (error) {
      if (error.code === '23505')
        return res.status(409).json({ error: 'Este email já está registado' });
      throw error;
    }

    const token = crypto.randomBytes(32).toString('hex');
    sessions.set(token, { userId: data.id, email: data.email, nome: data.nome, createdAt: Date.now() });
    saveState();
    res.json({ token, nome: data.nome, email: data.email });
  } catch (err) {
    console.error('Register error:', err.message);
    res.status(500).json({ error: 'Erro ao criar conta. Tente novamente.' });
  }
});

app.post('/api/auth/login', async (req, res) => {
  const { email, senha } = req.body;
  if (!email || !senha)
    return res.status(400).json({ error: 'Email e senha são obrigatórios' });

  try {
    const { data, error } = await supabase
      .from('usuarios')
      .select('id, nome, email, senha_hash, role')
      .eq('email', email.toLowerCase().trim())
      .single();

    if (error || !data)
      return res.status(401).json({ error: 'Email ou senha incorretos' });

    const ok = await bcrypt.compare(senha, data.senha_hash);
    if (!ok)
      return res.status(401).json({ error: 'Email ou senha incorretos' });

    const token = crypto.randomBytes(32).toString('hex');
    sessions.set(token, { userId: data.id, email: data.email, nome: data.nome, createdAt: Date.now() });
    saveState();
    res.json({ token, nome: data.nome, email: data.email });
  } catch (err) {
    console.error('Login error:', err.message);
    res.status(500).json({ error: 'Erro ao fazer login. Tente novamente.' });
  }
});

app.get('/api/auth/me', (req, res) => {
  const token = (req.headers.authorization || '').replace('Bearer ', '');
  const session = sessions.get(token);
  if (!session) return res.status(401).json({ error: 'Não autenticado' });
  res.json({ nome: session.nome, email: session.email });
});

app.get('/api/user/devices', async (req, res) => {
  const token = (req.headers.authorization || '').replace('Bearer ', '');
  const session = sessions.get(token);
  if (!session) return res.status(401).json({ error: 'Não autenticado' });

  const { data, error } = await supabase
    .from('ativacoes')
    .select('id, nome_dispositivo, nome_cliente, mac_address, device_key, plano, validade, ativo, modelo_dispositivo, criado_em')
    .eq('usuario_id', session.userId)
    .eq('ativo', true)
    .order('criado_em', { ascending: false });

  if (error) return res.status(500).json({ error: 'Erro ao buscar dispositivos' });
  res.json(data || []);
});

app.post('/api/auth/logout', (req, res) => {
  const token = (req.headers.authorization || '').replace('Bearer ', '');
  sessions.delete(token);
  saveState();
  res.json({ success: true });
});

app.post('/api/auth/change-password', async (req, res) => {
  const token = (req.headers.authorization || '').replace('Bearer ', '');
  const session = sessions.get(token);
  if (!session) return res.status(401).json({ error: 'Não autenticado' });

  const { currentPassword, newPassword } = req.body;
  if (!currentPassword || !newPassword) return res.status(400).json({ error: 'Campos obrigatórios em falta' });
  if (newPassword.length < 6) return res.status(400).json({ error: 'A nova senha deve ter pelo menos 6 caracteres' });

  const { data: user, error: fetchErr } = await supabase
    .from('usuarios').select('senha_hash').eq('id', session.userId).single();
  if (fetchErr || !user) return res.status(500).json({ error: 'Erro ao verificar utilizador' });

  const ok = await bcrypt.compare(currentPassword, user.senha_hash);
  if (!ok) return res.status(401).json({ error: 'Senha atual incorreta' });

  const newHash = await bcrypt.hash(newPassword, 12);
  const { error: updateErr } = await supabase
    .from('usuarios').update({ senha_hash: newHash }).eq('id', session.userId);
  if (updateErr) return res.status(500).json({ error: 'Erro ao atualizar senha' });

  res.json({ success: true });
});

app.get('/api/auth/profile', async (req, res) => {
  const token = (req.headers.authorization || '').replace('Bearer ', '');
  const session = sessions.get(token);
  if (!session) return res.status(401).json({ error: 'Não autenticado' });

  const { data, error } = await supabase
    .from('usuarios')
    .select('nome, email, telefone, data_nascimento, pais, email_secundario')
    .eq('id', session.userId)
    .single();
  if (error) return res.status(500).json({ error: 'Erro ao buscar perfil' });
  res.json(data);
});

app.post('/api/auth/update-profile', async (req, res) => {
  const token = (req.headers.authorization || '').replace('Bearer ', '');
  const session = sessions.get(token);
  if (!session) return res.status(401).json({ error: 'Não autenticado' });

  const { nome, email_secundario, pais, telefone, data_nascimento } = req.body;
  if (!nome || !nome.trim()) return res.status(400).json({ error: 'Nome é obrigatório' });

  try {
    const { error } = await supabase
      .from('usuarios')
      .update({ nome: nome.trim(), email_secundario: email_secundario || null, pais: pais || null, telefone: telefone || null, data_nascimento: data_nascimento || null })
      .eq('id', session.userId);
    if (error) throw error;
    session.nome = nome.trim();
    res.json({ success: true, nome: nome.trim() });
  } catch (err) {
    res.status(500).json({ error: 'Erro ao atualizar perfil' });
  }
});

// ── FORGOT / RESET PASSWORD ───────────────────────────────────────────────────
function maskEmail(email) {
  if (!email) return '';
  const [local, domain] = email.split('@');
  const maskedLocal = local.length <= 2 ? local[0] + '*' : local.slice(0, 2) + '*'.repeat(Math.min(local.length - 2, 4));
  const [domainName, ...tld] = domain.split('.');
  const maskedDomain = domainName[0] + '*'.repeat(Math.min(domainName.length - 1, 3)) + '.' + tld.join('.');
  return `${maskedLocal}@${maskedDomain}`;
}

function buildResetEmailHtml(nomeExibido, resetLink) {
  return `<!DOCTYPE html>
<html lang="pt-BR">
<head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head>
<body style="margin:0;padding:0;background:#0f172a;font-family:Arial,sans-serif;">
  <table width="100%" cellpadding="0" cellspacing="0" style="background:#0f172a;padding:40px 16px;">
    <tr><td align="center">
      <table width="520" cellpadding="0" cellspacing="0" style="background:#1e293b;border-radius:16px;overflow:hidden;max-width:520px;">
        <tr><td style="background:linear-gradient(135deg,#6d28d9,#4f46e5);padding:32px 40px;text-align:center;">
          <span style="color:#fff;font-size:24px;font-weight:900;letter-spacing:1px;">ALPHA PRIME</span>
        </td></tr>
        <tr><td style="padding:36px 40px;">
          <p style="margin:0 0 16px;color:#94a3b8;font-size:14px;">Olá, <strong style="color:#e2e8f0;">${nomeExibido}</strong></p>
          <p style="margin:0 0 24px;color:#cbd5e1;font-size:15px;line-height:1.6;">
            Recebemos um pedido de redefinição de senha para a sua conta.<br>
            Clique no botão abaixo para criar uma nova senha. O link expira em <strong>1 hora</strong>.
          </p>
          <div style="text-align:center;margin:32px 0;">
            <a href="${resetLink}" style="display:inline-block;background:linear-gradient(135deg,#6d28d9,#4f46e5);color:#fff;text-decoration:none;padding:14px 36px;border-radius:10px;font-size:15px;font-weight:700;letter-spacing:.5px;">
              Redefinir minha senha
            </a>
          </div>
          <p style="margin:0 0 8px;color:#64748b;font-size:12px;line-height:1.6;">
            Se você não solicitou a redefinição, ignore este email. Sua senha permanece a mesma.
          </p>
          <p style="margin:0;color:#475569;font-size:11px;">
            Ou copie e cole este link no seu navegador:<br>
            <a href="${resetLink}" style="color:#818cf8;word-break:break-all;">${resetLink}</a>
          </p>
        </td></tr>
        <tr><td style="background:#0f172a;padding:20px 40px;text-align:center;">
          <p style="margin:0;color:#334155;font-size:11px;">&copy; 2026 Alpha Prime TV. Todos os direitos reservados.</p>
        </td></tr>
      </table>
    </td></tr>
  </table>
</body>
</html>`;
}

app.post('/api/auth/forgot-password', async (req, res) => {
  const { email, sendTo } = req.body;
  if (!email) return res.status(400).json({ error: 'Email é obrigatório' });

  try {
    const { data: user } = await supabase
      .from('usuarios')
      .select('id, nome, email, email_secundario')
      .eq('email', email.toLowerCase().trim())
      .single();

    // User not found: return generic success (avoid enumeration)
    if (!user) return res.json({ success: true });

    const hasSecondary = !!user.email_secundario;

    // Step 1: no sendTo yet and secondary exists → ask user to choose
    if (!sendTo && hasSecondary) {
      return res.json({
        step: 'choose',
        maskedPrimary: maskEmail(user.email),
        maskedSecondary: maskEmail(user.email_secundario)
      });
    }

    // Step 2: send to chosen destination (or primary if no secondary)
    const targetEmail = (sendTo === 'secondary' && user.email_secundario)
      ? user.email_secundario
      : user.email;

    const token = crypto.randomBytes(32).toString('hex');
    resetTokens.set(token, { email: user.email, expiresAt: Date.now() + 3600_000 });

    const resetLink = `${APP_BASE_URL}/login.html?reset=${token}`;

    await resend.emails.send({
      from: 'Alpha Prime Suporte <suporte@alphaprimetv.com>',
      to: targetEmail,
      subject: 'Redefinição de senha — Alpha Prime',
      html: buildResetEmailHtml(user.nome || 'Cliente', resetLink)
    });

    res.json({ success: true });
  } catch (err) {
    console.error('[forgot-password] Erro:', err.message);
    res.status(500).json({ error: 'Erro ao processar solicitação. Tente novamente.' });
  }
});

app.post('/api/auth/delete-account', async (req, res) => {
  const token = (req.headers.authorization || '').replace('Bearer ', '');
  const session = sessions.get(token);
  if (!session) return res.status(401).json({ error: 'Não autenticado' });

  const { password } = req.body;
  if (!password) return res.status(400).json({ error: 'Senha é obrigatória para confirmar a exclusão' });

  try {
    const { data: user, error: fetchErr } = await supabase
      .from('usuarios').select('senha_hash').eq('id', session.userId).single();
    if (fetchErr || !user) return res.status(500).json({ error: 'Erro ao verificar utilizador' });

    const ok = await bcrypt.compare(password, user.senha_hash);
    if (!ok) return res.status(401).json({ error: 'Senha incorreta' });

    const now = new Date().toISOString();
    const { error: updateErr } = await supabase
      .from('usuarios')
      .update({ ativo: false, data_autoexclusao: now })
      .eq('id', session.userId);
    if (updateErr) throw updateErr;

    sessions.delete(token);
    saveState();
    res.json({ success: true });
  } catch (err) {
    console.error('[delete-account] Erro:', err.message);
    res.status(500).json({ error: 'Erro interno. Tente novamente.' });
  }
});

app.post('/api/auth/reset-password', async (req, res) => {
  const { token, newPassword } = req.body;
  if (!token || !newPassword)
    return res.status(400).json({ error: 'Token e nova senha são obrigatórios' });
  if (newPassword.length < 6)
    return res.status(400).json({ error: 'A senha deve ter pelo menos 6 caracteres' });

  const record = resetTokens.get(token);
  if (!record) return res.status(400).json({ error: 'Link inválido ou já utilizado' });
  if (Date.now() > record.expiresAt) {
    resetTokens.delete(token);
    return res.status(400).json({ error: 'Este link expirou. Solicite um novo.' });
  }

  try {
    const newHash = await bcrypt.hash(newPassword, 12);
    const { error } = await supabase
      .from('usuarios')
      .update({ senha_hash: newHash })
      .eq('email', record.email);

    if (error) throw error;

    resetTokens.delete(token);
    res.json({ success: true });
  } catch (err) {
    console.error('[reset-password] Erro:', err.message);
    res.status(500).json({ error: 'Erro ao redefinir senha. Tente novamente.' });
  }
});

// ── CONTACT FORM ──────────────────────────────────────────────────────────────
app.post('/api/contact', async (req, res) => {
  const { name, email, phone, message } = req.body;
  if (!name || !email || !message) {
    return res.status(400).json({ error: 'Nome, email e mensagem são obrigatórios.' });
  }

  try {
    await resend.emails.send({
      from: 'Alpha Prime Site <suporte@alphaprimetv.com>',
      to: 'contato@alphaprimetv.com',
      reply_to: email,
      subject: `Contacto do site — ${name}`,
      html: `
        <div style="font-family:sans-serif;max-width:600px;margin:0 auto">
          <h2 style="color:#7c3aed">Nova mensagem do formulário de contato</h2>
          <table style="width:100%;border-collapse:collapse">
            <tr><td style="padding:8px;font-weight:bold;width:100px">Nome:</td><td style="padding:8px">${name}</td></tr>
            <tr style="background:#f8f8f8"><td style="padding:8px;font-weight:bold">Email:</td><td style="padding:8px"><a href="mailto:${email}">${email}</a></td></tr>
            <tr><td style="padding:8px;font-weight:bold">Telefone:</td><td style="padding:8px">${phone || '—'}</td></tr>
            <tr style="background:#f8f8f8"><td style="padding:8px;font-weight:bold;vertical-align:top">Mensagem:</td><td style="padding:8px;white-space:pre-wrap">${message}</td></tr>
          </table>
          <hr style="border:none;border-top:1px solid #e5e7eb;margin-top:24px">
          <p style="color:#9ca3af;font-size:12px">Enviado em ${new Date().toLocaleString('pt-BR', { timeZone: 'America/Sao_Paulo' })} via alphaprimetv.com</p>
        </div>
      `
    });
    res.json({ success: true });
  } catch (err) {
    console.error('[contact] Erro ao enviar email:', err.message);
    res.status(500).json({ error: 'Não foi possível enviar a mensagem. Tente novamente.' });
  }
});

const port = process.env.PORT || 3000;
app.listen(port, () => console.log(`Alpha Prime dashboard rodando em http://localhost:${port}`));

// Expiração automática: verifica a cada minuto e desativa ativações vencidas
setInterval(async () => {
  const { error, count } = await supabase
    .from('ativacoes')
    .update({ ativo: false })
    .eq('ativo', true)
    .not('validade', 'is', null)
    .lt('validade', new Date().toISOString())
    .select('id', { count: 'exact', head: true });
  if (error) console.error('[expiry] Erro ao expirar ativações:', error.message);
  else if (count > 0) console.log(`[expiry] ${count} ativação(ões) expirada(s)`);
}, 60 * 1000);
