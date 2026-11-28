package kawaii.viey.browser;

import android.content.Context;

/**
 * 虚拟鼠标：在页面内注入一个可拖动光标，用它在悬停敏感的元素上完成点击。
 *
 * 目的：移动端没有 hover，很多站点的下拉菜单、悬浮工具条只能靠鼠标悬停触发。
 * 开启后手势约定为：
 * - 单指拖动：移动光标（页面不滚动）；
 * - 单指点按：在光标所指元素上依次派发 mousedown / mouseup / click；
 * - 双指滑动：事件原样交给页面，仍可正常滚动。
 *
 * 全部逻辑在页面内完成，不产生任何额外资源请求，体积开销仅一段脚本。
 */
public class VirtualMouse {

	private static final String KEY_ON = "virtual_mouse";

	public static boolean isOn(Context c) {
		return VieYApp.getPrefs(c).getBoolean(KEY_ON, false);
	}

	public static void setOn(Context c, boolean on) {
		VieYApp.getPrefs(c).edit().putBoolean(KEY_ON, on).apply();
	}

	/** 关闭并移除页面上已注入的光标 */
	public static final String OFF_SCRIPT =
		"(function(){try{if(window.__vieyMouseOff)window.__vieyMouseOff();}catch(e){}})();";

	/** 注入并启用光标，重复调用不会产生第二个光标 */
	public static final String SCRIPT =
		"(function(){"
			+ "if(window.__vieyMouse)return 'on';window.__vieyMouse=1;"
			+ "var cur=document.createElement('div');"
			+ "cur.style.cssText='position:fixed;left:0;top:0;width:28px;height:28px;"
			+ "z-index:2147483647;pointer-events:none;will-change:transform;"
			+ "filter:drop-shadow(0 1px 2px rgba(0,0,0,.5))';"
			+ "cur.innerHTML='<svg width=\"20\" height=\"26\" viewBox=\"0 0 20 26\">"
			+ "<path d=\"M2 2 L2 20 L7 15 L11 24 L15 22 L11 13 L18 13 Z\" "
			+ "fill=\"#fff\" stroke=\"#111\" stroke-width=\"2\"/></svg>';"
			+ "var x=Math.round(innerWidth/2)-14,y=Math.round(innerHeight/2)-13,hov=null;"
			+ "function place(){cur.style.transform='translate('+x+'px,'+y+'px)';}"
			+ "function clearHover(){if(hov){try{hov.style.outline='';hov.style.outlineOffset='';}catch(e){}hov=null;}}"
			+ "function hover(){"
			+ "var el=document.elementFromPoint(x+10,y+10);"
			+ "if(el===hov)return el;"
			+ "clearHover();hov=el;"
			+ "if(el&&el.style){try{el.style.outline='2px solid rgba(76,141,255,.85)';"
			+ "el.style.outlineOffset='1px';}catch(e){}}"
			+ "return el;"
			+ "}"
			+ "function fire(el){"
			+ "if(!el)return;"
			+ "try{el.scrollIntoView&&0;}catch(e){}"
			+ "['mousemove','mousedown','mouseup','click'].forEach(function(t){"
			+ "var ev=new MouseEvent(t,{bubbles:true,cancelable:true,view:window,"
			+ "clientX:x+10,clientY:y+10});el.dispatchEvent(ev);});"
			+ "try{el.focus&&el.focus();}catch(e){}"
			+ "}"
			+ "var dragging=false,sx=0,sy=0,cx=0,cy=0,moved=0,multi=false;"
			+ "function onStart(e){"
			+ "if(e.touches.length>1){multi=true;return;}"
			+ "multi=false;dragging=true;moved=0;"
			+ "sx=cx=e.touches[0].clientX;sy=cy=e.touches[0].clientY;"
			+ "clearHover();"
			+ "}"
			+ "function onMove(e){"
			+ "if(!dragging||multi||e.touches.length>1){multi=true;dragging=false;return;}"
			+ "e.preventDefault();e.stopPropagation();"
			+ "var tx=e.touches[0].clientX,ty=e.touches[0].clientY;"
			+ "var dx=tx-cx,dy=ty-cy;cx=tx;cy=ty;"
			+ "x=Math.max(-4,Math.min(innerWidth-24,x+dx));"
			+ "y=Math.max(-4,Math.min(innerHeight-26,y+dy));"
			+ "moved+=Math.abs(dx)+Math.abs(dy);"
			+ "place();hover();"
			+ "}"
			+ "function onEnd(e){"
			+ "if(!dragging){multi=false;return;}"
			+ "dragging=false;"
			+ "if(moved<6){var el=hover();fire(el);}"
			+ "clearHover();"
			+ "}"
			+ "document.addEventListener('touchstart',onStart,{capture:true,passive:false});"
			+ "document.addEventListener('touchmove',onMove,{capture:true,passive:false});"
			+ "document.addEventListener('touchend',onEnd,{capture:true,passive:false});"
			+ "document.addEventListener('touchcancel',onEnd,{capture:true,passive:false});"
			+ "(document.body||document.documentElement).appendChild(cur);"
			+ "place();"
			+ "window.__vieyMouseOff=function(){"
			+ "try{document.removeEventListener('touchstart',onStart,true);"
			+ "document.removeEventListener('touchmove',onMove,true);"
			+ "document.removeEventListener('touchend',onEnd,true);"
			+ "document.removeEventListener('touchcancel',onEnd,true);}catch(e){}"
			+ "clearHover();try{cur.remove();}catch(e){}"
			+ "window.__vieyMouse=0;window.__vieyMouseOff=null;"
			+ "};"
			+ "return 'on';})();";
}
