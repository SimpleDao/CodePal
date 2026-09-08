// 验证修复前:点击监听器结尾用 }}}); (多一个 }),应报语法错误
function escHtml(s){return s;}
function attrEsc(s){return s;}
document.addEventListener('click',function(e){
var a=e.target&&e.target.closest?e.target.closest('a'):null;
if(a){var h=a.getAttribute('href')||'';
if(/^https?:/i.test(h)){e.preventDefault();if(window.openUrl)window.openUrl(h);}
else if(h.indexOf('file://')===0){e.preventDefault();var fp=decodeURIComponent(h.substring(7));if(window.openFile)window.openFile(fp);}
else if(a.getAttribute('data-file')){e.preventDefault();var fp2=a.getAttribute('data-file');if(window.openFile)window.openFile(fp2);}
}}});
function linkifyFilePaths(root){
var cont=(root&&root.querySelectorAll)?root:document;
var nodes=cont.querySelectorAll('.bubble *, .tool-card-body *');
var winPath=/([a-zA-Z]:[\/][^\s:;"'<>|*?]+\.[a-zA-Z0-9]{1,10}(?::\d+)?)/g;
var unixPath=/(?<!["'<>(\[])(\/[a-zA-Z0-9_\-./]+\.[a-zA-Z0-9]{1,10}(?::\d+)?)/g;
var relPath=/(?<!["'<>(\/a-zA-Z0-9_\-.:])(src\/|com\/|test\/|main\/|app\/|lib\/|config\/|[a-zA-Z0-9_\-]+\/[a-zA-Z0-9_\-./]+\.[a-zA-Z]{1,10}(?::\d+)?)/g;
for(var i=0;i<nodes.length;i++){var el=nodes[i];
if(el.tagName==='A'||el.tagName==='BUTTON'||el.tagName==='CODE'&&el.parentElement&&el.parentElement.tagName==='PRE')continue;
if(el.childNodes.length!==1||el.firstChild.nodeType!==3)continue;
var txt=el.textContent;if(!txt||txt.length>500)continue;
var replaced=false;var html=escHtml(txt);
html=html.replace(winPath,function(m){replaced=true;return '<a href="file://'+encodeURI(m)+'" data-file="'+attrEsc(m)+'" style="color:var(--accent,#5865F2);text-decoration:underline;cursor:pointer;">'+escHtml(m)+'</a>';});
html=html.replace(unixPath,function(m){replaced=true;return '<a href="file://'+encodeURI(m)+'" data-file="'+attrEsc(m)+'" style="color:var(--accent,#5865F2);text-decoration:underline;cursor:pointer;">'+escHtml(m)+'</a>';});
if(replaced){el.innerHTML=html;}}
}
console.log('JS syntax OK');
