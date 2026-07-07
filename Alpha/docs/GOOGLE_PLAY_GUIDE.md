# 🚀 Guia de Publicação - Google Play Store

Procedimento completo para publicar o Velvet IPTV no Google Play Store.

## 📋 Pré-requisitos

1. **Conta Google Play Developer**
   - Acesse: https://play.google.com/console
   - Taxa única: $25 USD
   - Pagamento com cartão de crédito

2. **Certificado Digital (Keystore)**
   - Necessário para assinar o APK
   - **IMPORTANTE**: Mantenha em local seguro

3. **Documentação Legal**
   - Política de Privacidade
   - Termos de Uso
   - EULA

## 🔐 Criar Keystore (Uma única vez)

```bash
keytool -genkey -v -keystore velvet-iptv.keystore \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias velvet-key
```

**Informações Necessárias:**
- Nome: Velvet IPTV
- Organização: Seu Nome/Empresa
- Cidade: Sua Cidade
- Estado: Seu Estado
- País: BR
- Senha: (guarde em lugar seguro!)

## 📦 Gerar APK/AAB Assinado

### Opção 1: AAB (Recomendado para Google Play)

1. Abra Android Studio
2. Build → Generate Signed App Bundle
3. Selecione o Keystore criado
4. Escolha `release` como build variant
5. Clique em Finish

Resultado: `app-release.aab`

### Opção 2: APK via Gradle

```bash
./gradlew bundleRelease
```

Arquivo gerado: `app/build/outputs/bundle/release/app-release.aab`

## 📱 Preparar Informações do App

### Descrição Curta (80 caracteres)
```
Velvet IPTV: Streaming profissional de TV ao vivo com EPG
```

### Descrição Completa (4000 caracteres)
```
🎬 Velvet IPTV - A Melhor Plataforma de Streaming IPTV

Assistir TV nunca foi tão fácil! Velvet IPTV oferece a melhor 
experiência de streaming com:

✨ Funcionalidades:
• Milhares de canais de TV ao vivo
• Qualidade até 4K Ultra HD
• EPG (Guia de Programação) em tempo real
• Gravação de programas (PVR)
• Sincronização entre dispositivos
• Modo offline
• Suporte a VPN
• Interface intuitiva e rápida
• Sem publicidade

📺 Compatibilidade:
• Android 8.0+
• Conexão mínima: 10 Mbps
• Otimizado para TV e smartphones

🔒 Segurança:
• Criptografia end-to-end
• Proteção de dados
• Sem coleta de informações pessoais

📞 Suporte:
• Email: contato.velvetapp@gmail.com
• Resposta em até 24 horas

Baixe Velvet IPTV agora e experimente o futuro do streaming!
```

## 📸 Assets Necessários

### Ícone do App (512x512 px)
- Formato: PNG
- Sem transparência
- Sem arredondamento (Android adiciona)

### Screenshots (até 8)
- Mínimo: 320x426 px
- Máximo: 3840x2160 px
- Recomendado: 1080x1920 px (9:16)

**Exemplos de screenshots:**
1. Tela inicial com canais
2. Player em ação
3. EPG
4. Favoritos
5. Qualidade e opções

### Feature Graphic (1024x500 px)
- Imagem promocional
- Mostra melhor funcionalidade

### Vídeo Promocional (Opcional)
- YouTube: 30 segundos
- Máximo: 500 MB
- Recomendado: 1920x1080 ou vertical

## 📝 Etapas de Publicação

### 1. Criar Aplicativo no Google Play Console

```
1. Acesse: https://play.google.com/console
2. Clique em "Criar aplicativo"
3. Nome: Velvet IPTV
4. Idioma padrão: Português (Brasil)
5. Tipo de aplicativo: App (não game)
6. Preencha informações gerais
```

### 2. Ir para "Lançamentos"

```
Releases → Create new release → Production
```

### 3. Upload do AAB

```
1. Arraste ou selecione app-release.aab
2. Aguarde validação (alguns minutos)
3. Verifique se não há erros
```

### 4. Preencher Detalhes do Aplicativo

**Categoria e Conteúdo:**
- Categoria: Entretenimento
- Classificação indicativa: Livre (PG-13)
- Conteúdo restrito: Nenhum

**Informações de Contato:**
- Email: contato.velvetapp@gmail.com
- Website: (opcional)
- URL de Privacy Policy: URL do seu site

**URL da Política de Privacidade**
```
https://seusite.com/privacy-policy
```

### 5. Categoria e Conteúdo

```
Clique em cada seção:
✓ Preencha todos os campos obrigatórios
✓ Confirme que não viola políticas
✓ Selecione classificação indicativa
```

### 6. Versão do Aplicativo

```
Nome da versão: 1.0.0
Notas da versão (o que é novo):
- Lançamento inicial
- Streaming em 4K
- EPG completo
- Multi-dispositivo
```

### 7. Preencher Informações do Lançamento

```
Versão: 1.0.0
Tipo de lançamento: Production (não Beta/Alpha)
Data de lançamento: Imediato ou agendado
```

### 8. Review e Publicação

```
1. Revise todas as informações
2. Confirme conformidade com políticas
3. Submeta para revisão
4. Aguarde aprovação (24-48 horas)
```

## ✅ Checklist Antes de Publicar

- [ ] App compila sem erros
- [ ] Todos os testes passam
- [ ] Versão do Android mínima é 8.0+
- [ ] Permissões apropriadas no AndroidManifest
- [ ] Descrição em português claro
- [ ] Screenshots de alta qualidade
- [ ] Ícone 512x512 PNG
- [ ] Política de privacidade preparada
- [ ] Email de contato funcional
- [ ] Keystore em local seguro (backup!)
- [ ] versionCode incrementado
- [ ] Nenhum hardcoded secrets/tokens

## 🚨 Políticas do Google Play

**Seu app DEVE:**
- ✅ Respeitar políticas de conteúdo
- ✅ Não distribuir conteúdo ilegal
- ✅ Ter política de privacidade clara
- ✅ Não coletar dados sensíveis sem consentimento
- ✅ Não enganar usuários
- ✅ Funcionar em Android 8.0+

**Seu app NÃO DEVE:**
- ❌ Distribuir conteúdo protegido por copyright
- ❌ Ter comportamento enganoso
- ❌ Coletar dados sem consentimento
- ❌ Conter malware ou spyware
- ❌ Manipular classificações/reviews
- ❌ Causar danos ao dispositivo

## 📊 Após Publicação

### Monitorar Métricas

```
Google Play Console → Estatísticas
- Instalações
- Desinstalações
- Crashes
- Avaliações/Reviews
```

### Responder Reviews

```
Console → Avaliações
- Responda comentários negativos
- Agradeça avaliações positivas
- Corrija problemas relatados
```

### Atualizações

```
Para atualizar:
1. Incremente versionCode
2. Atualize versionName
3. Gere novo AAB assinado
4. Siga mesmo processo anterior
5. Google Play detecta versão maior automaticamente
```

## 🆘 Troubleshooting

### App Rejeitado

**Causa comum**: Conteúdo violando políticas
**Solução**: 
- Leia feedback do Google
- Ajuste permissões/funcionalidades
- Reenvie

### Crash em Alguns Dispositivos

**Solução**:
- Verifique logs no Play Console
- Teste em múltiplos dispositivos
- Use App Bundle (não APK)

### App Não Aparece para Alguns Países

**Possíveis razões:**
- Selecionou países específicos (verifique)
- Dispositivos/Android muito antigos
- Restrições regionais aplicadas

## 📞 Suporte Google Play

- Email: https://support.google.com/googleplay/contact/play_console_support
- Fórum: https://support.google.com/googleplay/?hl=pt-BR
- Documentação: https://developer.android.com/distribute

---

**Boa sorte com o lançamento! 🚀**
