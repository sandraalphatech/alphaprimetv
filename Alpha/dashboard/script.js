// Smooth scroll for navigation links
document.querySelectorAll('a[href^="#"]').forEach(anchor => {
    anchor.addEventListener('click', function (e) {
        e.preventDefault();
        const target = document.querySelector(this.getAttribute('href'));
        if (target) {
            target.scrollIntoView({ behavior: 'smooth' });
        }
    });
});

// Form submission -> email via /api/contact
document.querySelector('#contact-form')?.addEventListener('submit', async function(e) {
    e.preventDefault();

    const formData = new FormData(this);
    const { name, email, phone, message } = Object.fromEntries(formData);
    const submitBtn = this.querySelector('.contact-submit');
    const feedback = this.querySelector('.form-feedback');

    submitBtn.disabled = true;
    const originalText = submitBtn.textContent;
    submitBtn.textContent = 'Enviando...';
    feedback.textContent = '';
    feedback.className = 'form-feedback';

    try {
        const res = await fetch('/api/contact', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name, email, phone, message })
        });
        const data = await res.json();

        if (res.ok && data.success) {
            feedback.textContent = 'Mensagem enviada com sucesso! Entraremos em contato em breve.';
            feedback.className = 'form-feedback success';
            this.reset();
        } else {
            feedback.textContent = data.error || 'Erro ao enviar. Tente novamente.';
            feedback.className = 'form-feedback error';
        }
    } catch {
        feedback.textContent = 'Erro de conexão. Verifique sua internet e tente novamente.';
        feedback.className = 'form-feedback error';
    } finally {
        submitBtn.disabled = false;
        submitBtn.textContent = originalText;
    }
});

// Navbar background on scroll — use class so CSS variables (incl. theme) control colors
const navbar = document.querySelector('.navbar');
if (navbar) {
    const updateNavScroll = () => navbar.classList.toggle('navbar-scrolled', window.scrollY > 50);
    window.addEventListener('scroll', updateNavScroll);
    updateNavScroll();
}

// Menu toggle for mobile
const menuToggle = document.querySelector('.menu-toggle');
const navLinks = document.querySelector('.nav-links');

menuToggle?.addEventListener('click', function() {
    navLinks.classList.toggle('open');
});

// Close mobile menu when link is clicked
document.querySelectorAll('.nav-links a').forEach(link => {
    link.addEventListener('click', function() {
        if (window.getComputedStyle(menuToggle).display !== 'none') {
            navLinks.classList.remove('open');
        }
    });
});

// Intersection Observer for animations
const observerOptions = {
    threshold: 0.1,
    rootMargin: '0px 0px -50px 0px'
};

const observer = new IntersectionObserver(function(entries) {
    entries.forEach(entry => {
        if (entry.isIntersecting) {
            entry.target.style.animation = 'fadeInUp 0.6s ease forwards';
            observer.unobserve(entry.target);
        }
    });
}, observerOptions);

// Add animation to feature cards
document.querySelectorAll('.feature-card').forEach((card, index) => {
    card.style.opacity = '0';
    card.style.animationDelay = `${index * 0.1}s`;
    observer.observe(card);
});

// Add fadeInUp animation
const style = document.createElement('style');
style.textContent = `
    @keyframes fadeInUp {
        from {
            opacity: 0;
            transform: translateY(30px);
        }
        to {
            opacity: 1;
            transform: translateY(0);
        }
    }
`;
document.head.appendChild(style);

// Download button actions
document.querySelectorAll('.download-btn').forEach(btn => {
    btn.addEventListener('click', function(e) {
        e.preventDefault();
        const type = this.classList.contains('google-play') ? 'Google Play' : 'APK Direto';
        alert(`Download de ${type} iniciará em breve!`);
    });
});

// Pricing card selection
document.querySelectorAll('.pricing-card button').forEach(btn => {
    btn.addEventListener('click', function(e) {
        e.preventDefault();
        const planName = this.closest('.pricing-card').querySelector('h3').textContent;
        alert(`Você selecionou o plano ${planName}. Redirecionando para pagamento...`);
    });
});

// Lazy load images
if ('IntersectionObserver' in window) {
    const imageObserver = new IntersectionObserver((entries, observer) => {
        entries.forEach(entry => {
            if (entry.isIntersecting) {
                const img = entry.target;
                img.src = img.dataset.src;
                img.classList.add('loaded');
                observer.unobserve(img);
            }
        });
    });

    document.querySelectorAll('img[data-src]').forEach(img => imageObserver.observe(img));
}

// Analytics tracking (example)
function trackEvent(eventName, eventData) {
    console.log(`Event: ${eventName}`, eventData);
    // In production, send to analytics service
}

// Track scroll depth
let scrollDepth = 0;
window.addEventListener('scroll', function() {
    const scrollPercentage = (window.scrollY / (document.documentElement.scrollHeight - window.innerHeight)) * 100;

    if (scrollPercentage > 25 && scrollDepth < 25) {
        scrollDepth = 25;
        trackEvent('scroll_depth', { depth: 25 });
    }
    if (scrollPercentage > 50 && scrollDepth < 50) {
        scrollDepth = 50;
        trackEvent('scroll_depth', { depth: 50 });
    }
    if (scrollPercentage > 75 && scrollDepth < 75) {
        scrollDepth = 75;
        trackEvent('scroll_depth', { depth: 75 });
    }
});

// Add active class to navigation based on scroll position
window.addEventListener('scroll', function() {
    let current = '';
    const sections = document.querySelectorAll('section[id]');

    sections.forEach(section => {
        const sectionTop = section.offsetTop;
        if (pageYOffset >= sectionTop - 200) {
            current = section.getAttribute('id');
        }
    });

    document.querySelectorAll('.nav-links a').forEach(link => {
        link.classList.remove('active');
        if (link.getAttribute('href').slice(1) === current) {
            link.classList.add('active');
        }
    });
});

// Add active class styling
const navStyle = document.createElement('style');
navStyle.textContent = `
    .nav-links a.active {
        color: var(--primary-color);
        border-bottom: 2px solid var(--primary-color);
        padding-bottom: 2px;
    }
`;
document.head.appendChild(navStyle);

console.log('Velvet IPTV Dashboard initialized');
