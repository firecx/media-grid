// Тема выставляется до загрузки приложения: иначе тёмная тема открывается с белой вспышки.
// Отдельный файл, а не встроенный скрипт: политика безопасности (CSP) разрешает только скрипты с сервера.
(function () {
  var scheme = 'light';
  try {
    var saved = window.localStorage.getItem('mediagrid-color-scheme');
    if (saved === 'light' || saved === 'dark') {
      scheme = saved;
    } else if (window.matchMedia('(prefers-color-scheme: dark)').matches) {
      scheme = 'dark';
    }
  } catch (e) {
    // хранилище недоступно (например, запрещено настройками) — тема по умолчанию
  }
  document.documentElement.setAttribute('data-mantine-color-scheme', scheme);
})();
