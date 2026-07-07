# 🚀 Guia de Deploy - Velvet IPTV

Instruções para publicar o aplicativo Android e o dashboard web.

## 📱 Deploy do App Android

### Google Play Store

Veja [GOOGLE_PLAY_GUIDE.md](./GOOGLE_PLAY_GUIDE.md) para instruções completas.

**Resumo rápido:**
1. Criar conta Google Play Developer ($25)
2. Gerar AAB assinado: `./gradlew bundleRelease`
3. Upload no Google Play Console
4. Preencher informações do app
5. Enviar para revisão (24-48 horas)

### Distribuição Alternativa (GitHub Releases)

```bash
# 1. Gerar APK
./gradlew assembleRelease

# 2. Criar release no GitHub
gh release create v1.0.0 ./app/build/outputs/apk/release/app-release.apk

# 3. Usuários baixam diretamente
```

### App Stores Alternativos

- **F-Droid**: App open-source
- **Amazon Appstore**: Distribuição na Amazon
- **Samsung Galaxy Store**: Dispositivos Samsung

## 🌐 Deploy do Dashboard HTML

### Opção 1: GitHub Pages (Gratuito)

```bash
# 1. Criar repositório GitHub
git clone https://github.com/seuuser/velvet-iptv-web.git
cd velvet-iptv-web

# 2. Colocar arquivos do dashboard
# Copiar: index.html, styles.css, script.js

# 3. Configurar GitHub Pages
# Settings → Pages → Source: main branch /root

# 4. Acessar em
# https://seuuser.github.io/velvet-iptv-web/
```

### Opção 2: Vercel (Recomendado)

```bash
# 1. Instalar Vercel CLI
npm install -g vercel

# 2. Deploy
vercel

# 3. Seguir prompts
# Acessar: https://velvet-iptv.vercel.app

# 4. Configurar domínio personalizado
vercel domains add seudominio.com
```

### Opção 3: Netlify

```bash
# 1. Conectar ao GitHub
# https://app.netlify.com/

# 2. Selecionar repositório
# Repositório com dashboard

# 3. Configuração automática
# Deploy automático em cada push

# 4. Acessar em
# https://velvet-iptv.netlify.app/
```

### Opção 4: Seu Próprio Servidor

#### Nginx

```nginx
# /etc/nginx/sites-available/velvet-iptv

server {
    listen 80;
    server_name velvetiptv.com www.velvetiptv.com;

    root /var/www/velvet-iptv;
    index index.html;

    # Cache estático
    location ~* \.(css|js|jpg|jpeg|png|gif|ico|svg|woff|woff2)$ {
        expires 30d;
        add_header Cache-Control "public, immutable";
    }

    # Suporte a SPA (se usar Vue/React)
    location / {
        try_files $uri /index.html;
    }

    # SSL com Let's Encrypt
    listen 443 ssl http2;
    ssl_certificate /etc/letsencrypt/live/velvetiptv.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/velvetiptv.com/privkey.pem;
}
```

**Deploy:**
```bash
# 1. Copiar arquivos
scp -r dashboard/* user@servidor:/var/www/velvet-iptv/

# 2. Reiniciar Nginx
ssh user@servidor "sudo systemctl restart nginx"

# 3. SSL automático
sudo certbot certonly --nginx -d velvetiptv.com
```

#### Docker

```dockerfile
# Dockerfile
FROM nginx:alpine

COPY dashboard/ /usr/share/nginx/html/

EXPOSE 80 443

CMD ["nginx", "-g", "daemon off;"]
```

**Build e Deploy:**
```bash
# Build imagem
docker build -t velvet-iptv-web .

# Rodar localmente
docker run -p 80:80 velvet-iptv-web

# Push para Docker Hub
docker push seuuser/velvet-iptv-web

# Deploy em servidor
docker pull seuuser/velvet-iptv-web
docker run -d -p 80:80 --name velvet-iptv seuuser/velvet-iptv-web
```

## 🔐 HTTPS/SSL

### Let's Encrypt (Gratuito)

```bash
# 1. Instalar Certbot
sudo apt-get install certbot python3-certbot-nginx

# 2. Obter certificado
sudo certbot certonly --standalone -d velvetiptv.com -d www.velvetiptv.com

# 3. Renovação automática
sudo systemctl enable certbot.timer
sudo systemctl start certbot.timer
```

### Cloudflare (Recomendado)

1. Adicione domínio no Cloudflare
2. Atualize nameservers do seu registrador
3. Ative SSL (mesmo em Free tier)
4. Configure Page Rules para cache

## 📊 CDN e Performance

### Cloudflare

```
1. https://dash.cloudflare.com/
2. Adicionar site
3. Configurações:
   - Minify CSS/JS/HTML
   - Cache Everything
   - Compression Gzip
   - Browser Caching (30 dias)
```

### AWS CloudFront

```bash
# 1. Upload S3
aws s3 cp dashboard s3://velvet-iptv-web --recursive

# 2. Criar distribuição CloudFront
# Origem: S3 bucket
# Comportamento: Cache tudo
# TTL: 86400 (1 dia)

# 3. Acessar via CloudFront URL
# https://d123.cloudfront.net/
```

## 📈 Analytics

### Google Analytics

```html
<!-- Adicionar ao index.html antes de </head> -->
<script async src="https://www.googletagmanager.com/gtag/js?id=GA_ID"></script>
<script>
  window.dataLayer = window.dataLayer || [];
  function gtag(){dataLayer.push(arguments);}
  gtag('js', new Date());
  gtag('config', 'GA_ID');
</script>
```

### Hotjar (Opcional)

```html
<!-- Rastrear clicks e scrolls -->
<script>
    (function(h,o,t,j,a,r){
        h.hj=h.hj||function(){(h.hj.q=h.hj.q||[]).push(arguments)};
        h._hjSettings={hjid:HOTJAR_ID,hjsv:6};
        a=o.getElementsByTagName('head')[0];
        r=o.createElement('script');
        r.async=1;
        r.src=t+h._hjSettings.hjid+j+h._hjSettings.hjsv;
        a.appendChild(r);
    })(window,document,'//static.hotjar.com/c/hotjar-','.js?sv=');
</script>
```

## 🔍 SEO

### Meta Tags

```html
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <meta name="description" content="Velvet IPTV - Streaming profissional de TV ao vivo em 4K">
    <meta name="keywords" content="IPTV, TV ao vivo, streaming, 4K, EPG">
    <meta name="author" content="Velvet IPTV">
    
    <!-- Open Graph para redes sociais -->
    <meta property="og:title" content="Velvet IPTV">
    <meta property="og:description" content="A melhor plataforma de IPTV">
    <meta property="og:image" content="https://velvetiptv.com/og-image.png">
    <meta property="og:url" content="https://velvetiptv.com">
    
    <!-- Twitter Card -->
    <meta name="twitter:card" content="summary_large_image">
    <meta name="twitter:title" content="Velvet IPTV">
    <meta name="twitter:description" content="Streaming em 4K">
    <meta name="twitter:image" content="https://velvetiptv.com/twitter-image.png">
</head>
```

### robots.txt

```
# /dashboard/robots.txt
User-agent: *
Allow: /

Sitemap: https://velvetiptv.com/sitemap.xml
```

### sitemap.xml

```xml
<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
    <url>
        <loc>https://velvetiptv.com/</loc>
        <lastmod>2026-06-03</lastmod>
        <priority>1.0</priority>
    </url>
    <url>
        <loc>https://velvetiptv.com/#features</loc>
        <priority>0.8</priority>
    </url>
    <url>
        <loc>https://velvetiptv.com/#pricing</loc>
        <priority>0.8</priority>
    </url>
</urlset>
```

## 📋 Checklist Pré-Deploy

### Android App
- [ ] versionCode e versionName atualizados
- [ ] Sem warnings de compilação
- [ ] Testes passando
- [ ] ProGuard/R8 configurado
- [ ] Assinatura correta
- [ ] Screenshots em alta qualidade
- [ ] Descrição em português
- [ ] Política de privacidade pronta
- [ ] Contato de suporte funcional

### Dashboard Web
- [ ] Links funcionam
- [ ] Responsivo em mobile
- [ ] Performance >90 (Lighthouse)
- [ ] Sem console errors
- [ ] Meta tags completas
- [ ] Favicon configurado
- [ ] SSL/HTTPS ativo
- [ ] CDN ativo
- [ ] Analytics integrado

## 🔄 CI/CD Pipeline (Opcional)

### GitHub Actions para Deploy Automático

```yaml
# .github/workflows/deploy.yml
name: Deploy

on:
  push:
    branches: [main]

jobs:
  deploy-web:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2
      
      - name: Deploy to Vercel
        uses: amondnet/vercel-action@v20
        with:
          vercel-token: ${{ secrets.VERCEL_TOKEN }}
          vercel-args: '--prod'
          vercel-org-id: ${{ secrets.VERCEL_ORG_ID }}
          vercel-project-id: ${{ secrets.VERCEL_PROJECT_ID }}
```

## 📞 Monitoramento

### Uptime Monitoring

```
1. https://uptimerobot.com/
2. Criar monitor para:
   - https://velvetiptv.com/
   - API endpoint
3. Alertas por email se cair
```

### Error Tracking

```
1. https://sentry.io/
2. Integrar no JavaScript
3. Rastrear erros em produção
```

## 🔧 Troubleshooting Deploy

### Problema: App não aparece no Play Store
**Solução**: Verifique se contentRating foi preenchido

### Problema: Dashboard lento
**Solução**: 
- Habilitar GZIP compression
- Minificar CSS/JS
- Usar CDN
- Lazy load imagens

### Problema: CORS errors
**Solução**: Configurar CORS no servidor
```nginx
add_header Access-Control-Allow-Origin "*";
```

---

**Contato**: contato.velvetapp@gmail.com

Última atualização: Junho 2026
