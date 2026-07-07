// Gestão do header de autenticação — partilhado por todas as páginas
// Lê ap_user (novo sistema Supabase) ou ap_account (legado MAC)
(function () {
    const user    = JSON.parse(localStorage.getItem('ap_user')    || 'null');
    const account = JSON.parse(localStorage.getItem('ap_account') || 'null');

    const loginBtn     = document.getElementById('btn-header-login');
    const userWrap     = document.getElementById('nav-user-wrap');
    const dropdown     = document.getElementById('user-dropdown');
    const avatarLetter = document.getElementById('nav-avatar-letter');
    const usernameEl   = document.getElementById('nav-username');

    const loggedIn = user || account;
    const fullName = user ? user.nome : (account ? account.nickname : null);
    const nick     = fullName ? fullName.split(' ')[0] : null;

    if (loggedIn && nick) {
        if (loginBtn)      loginBtn.style.display  = 'none';
        if (userWrap)      userWrap.style.display   = 'flex';
        if (usernameEl)    usernameEl.textContent   = nick;
        if (avatarLetter)  avatarLetter.textContent = nick[0].toUpperCase();
    } else {
        if (loginBtn)  loginBtn.style.display = 'block';
        if (userWrap)  userWrap.style.display = 'none';
    }

    // "Entrar" → redireciona para login.html
    if (loginBtn) {
        loginBtn.addEventListener('click', function () {
            window.location.href = 'login.html';
        });
    }

    // Toggle dropdown
    const toggleBtn = document.getElementById('nav-user-toggle');
    if (toggleBtn) {
        toggleBtn.addEventListener('click', function (e) {
            e.stopPropagation();
            dropdown && dropdown.classList.toggle('open');
        });
    }

    // Fechar ao clicar fora
    document.addEventListener('click', function (e) {
        if (userWrap && !userWrap.contains(e.target)) {
            dropdown && dropdown.classList.remove('open');
        }
    });

    // Logout
    const logoutBtn = document.getElementById('dd-logout');
    if (logoutBtn) {
        logoutBtn.addEventListener('click', async function () {
            // Notifica o servidor para invalidar o token
            const token = user?.token;
            if (token) {
                try {
                    await fetch('/api/auth/logout', {
                        method: 'POST',
                        headers: { Authorization: 'Bearer ' + token }
                    });
                } catch (_) {}
            }
            localStorage.removeItem('ap_user');
            localStorage.removeItem('ap_account');
            window.location.href = 'index.html';
        });
    }
})();
