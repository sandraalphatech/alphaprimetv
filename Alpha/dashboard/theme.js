// Theme toggle — apply saved theme immediately to prevent flash
(function () {
    const saved = localStorage.getItem('velvet-theme') || 'dark';
    document.documentElement.setAttribute('data-theme', saved);
})();

var _logoMap = {
    'logoheader2.png': 'logoheader2-claro.png',
    'logoheader.png':  'logoheader-claro.png',
    'logo.png':        'logo-claro.png',
};

function _velvetUpdateLogos(theme) {
    document.querySelectorAll('img').forEach(function (img) {
        var src = img.getAttribute('src') || '';
        var filename = src.split('/').pop().split('?')[0];
        if (theme === 'light') {
            if (_logoMap[filename]) {
                img.setAttribute('src', src.replace(filename, _logoMap[filename]));
            }
        } else {
            var keys = Object.keys(_logoMap);
            for (var i = 0; i < keys.length; i++) {
                if (filename === _logoMap[keys[i]]) {
                    img.setAttribute('src', src.replace(filename, keys[i]));
                    break;
                }
            }
        }
    });
}

function _velvetUpdateToggles() {
    var theme = document.documentElement.getAttribute('data-theme') || 'dark';
    document.querySelectorAll('.theme-toggle').forEach(function (btn) {
        btn.textContent = theme === 'dark' ? '☀️' : '🌙';
        btn.title = theme === 'dark' ? 'Modo claro' : 'Modo escuro';
    });
}

function velvetToggleTheme() {
    var current = document.documentElement.getAttribute('data-theme') || 'dark';
    var next = current === 'dark' ? 'light' : 'dark';
    document.documentElement.setAttribute('data-theme', next);
    localStorage.setItem('velvet-theme', next);
    _velvetUpdateToggles();
    _velvetUpdateLogos(next);
}

document.addEventListener('DOMContentLoaded', function () {
    var theme = document.documentElement.getAttribute('data-theme') || 'dark';
    _velvetUpdateToggles();
    _velvetUpdateLogos(theme);
    document.querySelectorAll('.theme-toggle').forEach(function (btn) {
        btn.addEventListener('click', velvetToggleTheme);
    });
});
