# 🏗️ Arquitetura - Velvet IPTV

Documentação técnica da arquitetura do aplicativo.

## 📐 Padrão Arquitetural: MVVM

```
┌─────────────────────────────────────────┐
│         UI Layer (Composables)          │
│         (HomeScreen, PlayerScreen)      │
└──────────────────┬──────────────────────┘
                   │
                   ↓
┌─────────────────────────────────────────┐
│     ViewModel Layer (StateManagement)   │
│     (ChannelViewModel, PlayerViewModel) │
└──────────────────┬──────────────────────┘
                   │
                   ↓
┌─────────────────────────────────────────┐
│       Repository Layer (DataAccess)     │
│   (ChannelRepository, PlayerRepository) │
└──────────────────┬──────────────────────┘
                   │
          ┌────────┴────────┐
          ↓                 ↓
    ┌──────────┐      ┌──────────┐
    │ Local DB │      │ Remote   │
    │  (Room)  │      │ API      │
    └──────────┘      └──────────┘
```

## 🗂️ Estrutura de Pacotes

```
com.velvetiptv.app/
├── MainActivity.kt                    # Activity principal
│
├── data/                             # Data Layer
│   ├── api/
│   │   ├── ApiClient.kt              # Retrofit client
│   │   ├── ApiService.kt             # API endpoints
│   │   └── interceptors/
│   │       ├── AuthInterceptor.kt    # Autenticação
│   │       └── LoggingInterceptor.kt # Logging
│   │
│   ├── db/
│   │   ├── AppDatabase.kt            # Room database
│   │   ├── dao/
│   │   │   ├── ChannelDao.kt
│   │   │   ├── FavoriteDao.kt
│   │   │   └── HistoryDao.kt
│   │   └── entities/
│   │       ├── ChannelEntity.kt
│   │       ├── FavoriteEntity.kt
│   │       └── HistoryEntity.kt
│   │
│   ├── models/
│   │   ├── Channel.kt
│   │   ├── Program.kt
│   │   ├── Playlist.kt
│   │   └── EPGEntry.kt
│   │
│   └── repository/
│       ├── ChannelRepository.kt      # Lógica de dados de canais
│       ├── FavoriteRepository.kt     # Gerenciamento de favoritos
│       └── PlayerRepository.kt       # Gerenciamento de player
│
├── ui/                               # UI Layer
│   ├── navigation/
│   │   └── Navigation.kt             # NavHost e rotas
│   │
│   ├── screens/
│   │   ├── home/
│   │   │   └── HomeScreen.kt
│   │   ├── player/
│   │   │   └── PlayerScreen.kt
│   │   ├── epg/
│   │   │   └── EPGScreen.kt
│   │   ├── favorites/
│   │   │   └── FavoritesScreen.kt
│   │   └── settings/
│   │       └── SettingsScreen.kt
│   │
│   ├── components/
│   │   ├── ChannelCard.kt
│   │   ├── PlayerControls.kt
│   │   ├── EPGGrid.kt
│   │   └── SearchBar.kt
│   │
│   └── theme/
│       ├── Theme.kt
│       ├── Color.kt
│       └── Type.kt
│
├── viewmodel/                        # ViewModel Layer
│   ├── ChannelViewModel.kt           # Estados e lógica de canais
│   ├── PlayerViewModel.kt            # Controle do player
│   ├── FavoriteViewModel.kt          # Gerenciamento de favoritos
│   └── SettingsViewModel.kt          # Preferências do usuário
│
└── utils/                            # Utilitários
    ├── Constants.kt                  # Constantes
    ├── Extensions.kt                 # Extensões Kotlin
    ├── PreferenceManager.kt          # Preferências
    ├── Logger.kt                     # Logging
    └── ErrorHandler.kt               # Tratamento de erros
```

## 🔌 Data Flow

### Exemplo: Carregar Lista de Canais

```
1. User abre HomeScreen
        ↓
2. HomeScreen chama ChannelViewModel.loadChannels()
        ↓
3. ChannelViewModel chama ChannelRepository.getChannels()
        ↓
4. Repository tenta obter do banco local (Room)
        ↓
5. Se não existe/desatualizado, chama ApiService.getChannels()
        ↓
6. ApiService faz requisição HTTP com Retrofit
        ↓
7. Resposta é salva no banco local
        ↓
8. Repository retorna dados para ViewModel
        ↓
9. ViewModel atualiza State (Compose)
        ↓
10. UI recompõe com dados novos
```

## 🎯 ViewModels

### ChannelViewModel

```kotlin
class ChannelViewModel(
    private val repository: ChannelRepository
) : ViewModel() {
    
    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    val channels: StateFlow<List<Channel>> = _channels.asStateFlow()
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    fun loadChannels() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val data = repository.getChannels()
                _channels.value = data
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    fun search(query: String) {
        // Filtrar canais baseado em query
    }
}
```

## 🌐 API Integration

### Exemplo de Retrofit Service

```kotlin
interface ApiService {
    @GET("/channels")
    suspend fun getChannels(): List<ChannelResponse>
    
    @GET("/channels/{id}/epg")
    suspend fun getEPG(@Path("id") channelId: String): List<EPGResponse>
    
    @GET("/stream/{id}")
    suspend fun getStreamUrl(@Path("id") channelId: String): StreamResponse
    
    @POST("/favorites")
    suspend fun addFavorite(@Body favorite: FavoriteRequest): Response<Unit>
}
```

### Configuração do Retrofit

```kotlin
object ApiClient {
    private const val BASE_URL = "https://api.velvetiptv.com/"
    
    fun getInstance(): Retrofit {
        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .client(createOkHttpClient())
            .build()
    }
    
    private fun createOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(LoggingInterceptor())
            .addInterceptor(AuthInterceptor())
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
```

## 💾 Database (Room)

### Channel Entity

```kotlin
@Entity(tableName = "channels")
data class ChannelEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val logo: String,
    val category: String,
    val streamUrl: String,
    val isFavorite: Boolean = false,
    val lastWatched: Long = 0
)
```

### DAO (Data Access Object)

```kotlin
@Dao
interface ChannelDao {
    @Query("SELECT * FROM channels")
    suspend fun getAllChannels(): List<ChannelEntity>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChannels(channels: List<ChannelEntity>)
    
    @Query("SELECT * FROM channels WHERE isFavorite = 1")
    suspend fun getFavorites(): List<ChannelEntity>
}
```

## 🔐 Security Practices

### 1. SSL Pinning
```kotlin
.certificatePinner(CertificatePinner.Builder()
    .add("api.velvetiptv.com", "sha256/AAAAAAA...")
    .build())
```

### 2. Encrypted Shared Preferences
```kotlin
val masterKey = MasterKey.Builder(context)
    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
    .build()

val preferences = EncryptedSharedPreferences.create(
    context,
    "secret_prefs",
    masterKey,
    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
)
```

### 3. JWT Token Management
```kotlin
class AuthInterceptor(private val tokenManager: TokenManager) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokenManager.getToken()
        val request = chain.request().newBuilder()
            .addHeader("Authorization", "Bearer $token")
            .build()
        return chain.proceed(request)
    }
}
```

## 🧪 Testing Strategy

### Unit Tests (ViewModel)
```kotlin
@Test
fun testLoadChannels() = runTest {
    val mockRepository = mockk<ChannelRepository>()
    coEvery { mockRepository.getChannels() } returns mockChannels
    
    val viewModel = ChannelViewModel(mockRepository)
    viewModel.loadChannels()
    
    assertEquals(mockChannels, viewModel.channels.value)
}
```

### Integration Tests (Repository)
```kotlin
@Test
fun testGetChannelsFromDB() = runTest {
    val dao = mockk<ChannelDao>()
    coEvery { dao.getAllChannels() } returns listOf(mockChannel)
    
    val repository = ChannelRepository(dao, mockApiService)
    val result = repository.getChannels()
    
    assertEquals(mockChannel, result[0])
}
```

## 📊 State Management

### MutableStateFlow vs LiveData

**Preferência: StateFlow**
```kotlin
// ✅ Moderno e reativo
val channels: StateFlow<List<Channel>> = _channels.asStateFlow()

// ❌ Legacy
val channelsLive: LiveData<List<Channel>> = _channels
```

## 🔄 Lifecycle Integration

### ViewModel com Coroutines
```kotlin
class ChannelViewModel : ViewModel() {
    init {
        viewModelScope.launch {
            // Automatically cancelled when ViewModel is cleared
            loadChannels()
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        // Cleanup resources
    }
}
```

## 📈 Performance Optimization

### 1. Lazy Loading com Pagination
```kotlin
fun loadMoreChannels(page: Int) {
    viewModelScope.launch {
        val channels = repository.getChannels(page = page)
        _channels.value = (_channels.value + channels)
    }
}
```

### 2. Image Caching com Coil
```kotlin
AsyncImage(
    model = ImageRequest.Builder(LocalContext.current)
        .data(channel.logo)
        .crossfade(true)
        .build(),
    contentDescription = channel.name,
    modifier = Modifier.size(150.dp),
    contentScale = ContentScale.Crop,
    cachePolicy = CachePolicy.ENABLED
)
```

### 3. Database Indexing
```kotlin
@Entity(
    tableName = "channels",
    indices = [
        Index("id"),
        Index("category"),
        Index("isFavorite")
    ]
)
```

## 🚀 Deployment

### Build Variants
```gradle
buildTypes {
    debug {
        // Debug build com logging
    }
    release {
        // Minificação com ProGuard
        minifyEnabled true
        proguardFiles getDefaultProguardFile(...), 'proguard-rules.pro'
    }
}
```

---

**Última atualização**: Junho 2026
