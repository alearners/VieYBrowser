/* 移植 Vie 6.5.0 的 seimage 看图模式：点图放大、点空白退出 */
(function () {
    if (window.__vieImageView) return;
    window.__vieImageView = 1;
    var ID = 'vie_img_overlay';
    function open(src) {
        var old = document.getElementById(ID);
        if (old) old.parentNode.removeChild(old);
        var box = document.createElement('div');
        box.id = ID;
        box.style.cssText = 'position:fixed;inset:0;z-index:2147483000;background:rgba(0,0,0,.92);display:flex;align-items:center;justify-content:center;touch-action:pinch-zoom';
        var img = document.createElement('img');
        img.src = src;
        img.style.cssText = 'max-width:100%;max-height:100%;object-fit:contain';
        box.appendChild(img);
        box.addEventListener('click', function () { box.parentNode.removeChild(box); });
        document.body.appendChild(box);
    }
    function pick(e) {
        var t = e.target;
        var src = '';
        if (t && t.tagName === 'IMG') src = t.currentSrc || t.src;
        else if (t && t.style && t.style.backgroundImage) {
            var m = /url\(["']?([^"')]+)/.exec(t.style.backgroundImage);
            if (m) src = m[1];
        }
        if (!src || src.indexOf('data:image') === 0 && src.length < 64) return;
        e.preventDefault();
        e.stopPropagation();
        open(src);
    }
    document.addEventListener('click', pick, true);
})();
