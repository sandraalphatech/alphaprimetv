# 📱 Velvet IPTV - App Android

Aplicativo Android nativo para streaming de IPTV com interface moderna em Jetpack Compose.

## 🚀 Características

- **Kotlin + Jetpack Compose** - UI moderna e reativa
- **Material Design 3** - Design system atualizado
- **ExoPlayer** - Streaming de vídeo profissional
- **MVVM Architecture** - Separação clara de responsabilidades
- **Room Database** - Armazenamento local de favoritos
- **Retrofit + OkHttp** - Requisições HTTP otimizadas
- **Coil** - Carregamento eficiente de imagens
- **Hilt** - Injeção de dependências

## 📋 Requisitos

- **Android SDK**: 34 (compileSdk)
- **Mínimo**: Android 8.0 (API 24)
- **JDK**: 17 ou superior
- **Gradle**: 8.1.0 ou superior

## 🏗️ Estrutura do Projeto

```
app/
├── src/main/
│   ├── kotlin/com/velvetiptv/app/
│   │   ├── MainActivity.kt           # Activity principal
│   │   ├── ui/
│   │   │   ├── navigation/           # Navegação
│   │   │   ├── screens/              # Telas do app
│   │   │   │   ├── home/             # Lista de canais
│   │   │   │   ├── player/           # Player de vídeo
│   │   │   │   ├── epg/              # Guia de programação
│   │   │   │   ├── favorites/        # Canais favoritos
│   │   │   │   └── settings/         # Configurações
│   │   │   └── theme/                # Tema e cores
│   │   ├── data/
│   │   │   ├── api/                  # API client
│   │   │   ├── db/                   # Database
│   │   │   └── models/               # Data models
│   │   ├── viewmodel/                # ViewModels
│   │   └── utils/                    # Utilitários
│   └── res/
│       ├── values/                   # Strings e cores
│       ├── drawable/                 # Recursos visuais
│       └── mipmap/                   # Ícones
└── build.gradle.kts                  # Configuração Gradle
```

## 🛠️ Configuração e Build

### 1. Clone/Configure o Projeto

```bash
cd android
```

### 2. Sincronize Gradle

```bash
./gradlew sync
```

### 3. Build Debug

```bash
./gradlew assembleDebug
```

### 4. Build Release

```bash
./gradlew assembleRelease
```

## 📱 Telas Principais

### Home Screen
- Grade de canais (2 colunas)
- Busca de canais
- Categorias
- Histórico de visualização

### Player Screen
- Reprodutor de vídeo full HD
- Controles de qualidade (HD/Full HD/4K)
- EPG em tempo real
- Informações do canal

### EPG Screen
- Guia de programação eletrônico
- Horários dos programas
- Descrições
- Gravação de programas

### Favorites Screen
- Canais salvos
- Sincronização com servidor
- Reordenação

### Settings Screen
- Qualidade de vídeo
- HDR ativado/desativado
- Modo escuro
- Sobre o app

## 🔌 Integração com API

### Endpoints Esperados

```
GET /api/channels              # Lista de canais
GET /api/channels/:id/epg      # EPG de um canal
GET /api/stream/:channel       # URL de streaming
GET /api/categories            # Categorias
POST /api/favorites            # Salvar favorito
DELETE /api/favorites/:id      # Remover favorito
```

## 🎨 Tema e Design

### Cores Principais
- **Primary**: Purple #7c3aed
- **Secondary**: Pink #ec4899
- **Background**: Dark #0f172a
- **Surface**: #1e293b

### Tipografia
- **Headline Large**: 32sp, Bold
- **Body Large**: 16sp, Normal
- **Label Small**: 11sp, Medium

## 📚 Dependências Principais

```gradle
// Compose
androidx.compose:compose-bom:2023.10.00

// Navigation
androidx.navigation:navigation-compose:2.7.4

// Retrofit + OkHttp
com.squareup.retrofit2:retrofit:2.9.0
com.squareup.okhttp3:okhttp:4.11.0

// ExoPlayer
androidx.media3:media3-exoplayer:1.1.1

// Room
androidx.room:room-ktx:2.6.1

// Hilt
com.google.dagger:hilt-android:2.48
```

## 🔐 Segurança

- ✅ Certificado SSL pinning com OkHttp
- ✅ Proteção de dados sensíveis
- ✅ Autenticação via JWT
- ✅ Encriptação local com EncryptedSharedPreferences

## 📈 Performance

- **Lazy Loading**: Carregamento sob demanda
- **Image Caching**: Cache de imagens com Coil
- **Buffer Otimizado**: ExoPlayer com ajuste automático
- **Memory Efficient**: MVVM com lifecycle awareness

## 🧪 Testes

```bash
# Unit tests
./gradlew test

# UI tests
./gradlew connectedAndroidTest

# Coverage
./gradlew testDebugUnitTestCoverage
```

## 📦 Google Play Release

### Pre-release Checklist

- [ ] Código compilando sem warnings
- [ ] Testes passando (>80% coverage)
- [ ] Screenshots em alta qualidade
- [ ] Descrição em português
- [ ] Política de privacidade
- [ ] Contato/suporte disponível
- [ ] versionCode e versionName atualizados
- [ ] Release APK assinado

### Build Release

```bash
./gradlew clean bundleRelease
```

Isso gera um `.aab` (Android App Bundle) para upload no Google Play Console.

## 🐛 Troubleshooting

### Problema: Gradle sync falhando
**Solução**: 
```bash
./gradlew clean
./gradlew sync
```

### Problema: Erro de compilação Kotlin
**Solução**: Verifique se está usando JDK 17+
```bash
java -version
```

### Problema: Permissões em Android 12+
**Solução**: Garanta permissões no AndroidManifest.xml e peça em runtime

## 📞 Contato e Suporte

- **Email**: contato.velvetapp@gmail.com
- **Status**: Em desenvolvimento
- **Versão**: 1.0.0

## 📄 Licença

Todos os direitos reservados © 2026 Velvet IPTV

---

**Desenvolvido com ❤️ usando Kotlin e Jetpack Compose**
