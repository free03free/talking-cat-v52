(function(){
"use strict";
const KEY="learningMaxNumber", REPEAT_KEY="repeatEnabled", SPEED_KEY="speechSpeed", STEPS_KEY="ttsSteps", NORMAL_VOLUME_KEY="normalVolume", LEARNING_VOLUME_KEY="learningVolume";
const FEATURE_KEY="learningFeatures";
let maxNumber=parseInt(localStorage.getItem(KEY)||"10",10);
if(!Number.isFinite(maxNumber)||maxNumber<1)maxNumber=10;
if(maxNumber>1000)maxNumber=1000;
let speechSpeed=parseFloat(localStorage.getItem(SPEED_KEY)||"0.9");
let learningVolume=parseInt(localStorage.getItem(LEARNING_VOLUME_KEY)||"100",10);
if(!Number.isFinite(learningVolume)||learningVolume<0)learningVolume=90;
if(learningVolume>100)learningVolume=100;
let volumeLevel=learningVolume;
if(!Number.isFinite(speechSpeed))speechSpeed=0.9;
let features=Object.assign({harakat:false,madd:false,words:false,quiz:false},JSON.parse(localStorage.getItem(FEATURE_KEY)||"{}"));
let selectedNumber=1,selectedLetter=0,selectedColor=0,quizAnswer="",quizActive=false;
let heard="",pokes=0,pokeT=0,idleAt=Date.now(),talkDog=0;
pushNativePrefs();
const letters=[
 ["أ","أَلِف"],["ب","بَاء"],["ت","تَاء"],["ث","ثَاء"],["ج","جِيم"],["ح","حَاء"],["خ","خَاء"],["د","دَال"],["ذ","ذَال"],["ر","رَاء"],["ز","زَاي"],["س","سِين"],["ش","شِين"],["ص","صَاد"],["ض","ضَاد"],["ط","طَاء"],["ظ","ظَاء"],["ع","عَيْن"],["غ","غَيْن"],["ف","فَاء"],["ق","قَاف"],["ك","كَاف"],["ل","لَام"],["م","مِيم"],["ن","نُون"],["ه","هَاء"],["و","وَاو"],["ي","يَاء"]
];
const colors=[["أَحْمَر","#e74c3c"],["أَزْرَق","#3498db"],["أَصْفَر","#f1c40f"],["أَخْضَر","#2ecc71"],["بُرْتُقَالِي","#f39c12"],["بَنَفْسَجِي","#9b59b6"],["وَرْدِي","#ff7aa2"],["بُنِّي","#8b5a2b"],["أَسْوَد","#222"],["أَبْيَض","#fff"],["رَمَادِي","#95a5a6"],["ذَهَبِي","#d4ac0d"]];
const $=id=>document.getElementById(id);
function showSpeech(t){const s2=$("speech2");if(s2)s2.textContent=t}
const catEls=()=>[$("cat2"),$("cat3")];
let mouthT=null;
function mouthLoop(on){clearInterval(mouthT);mouthT=null;if(on){mouthT=setInterval(()=>{const v=(.25+Math.random()*.8).toFixed(2);catEls().forEach(c=>c&&c.style.setProperty("--mo",v))},120)}else catEls().forEach(c=>c&&c.style.setProperty("--mo",.3))}
function catSet(cls,on){catEls().forEach(c=>c&&c.classList.toggle(cls,!!on));if(cls==="talk")mouthLoop(!!on)}
function catOnce(cls,ms){catEls().forEach(c=>{if(!c)return;c.classList.remove(cls);void c.offsetWidth;c.classList.add(cls)});setTimeout(()=>catEls().forEach(c=>c&&c.classList.remove(cls)),ms)}
function wake(){idleAt=Date.now();if($("cat3").classList.contains("sleep")){catSet("sleep",false);document.querySelectorAll(".zzz").forEach(z=>z.remove())}}
function talk(text,speed){
 showSpeech(text);wake();
 if(window.TomNative&&TomNative.say){TomNative.say(text,Number(speed||speechSpeed),0);clearTimeout(talkDog);talkDog=setTimeout(()=>catSet("talk",false),20000)}
 else{catSet("talk",true);setTimeout(()=>catSet("talk",false),Math.min(2500,Math.max(500,String(text).length*90)))}
}
function activeVolume(){return learningVolume}
function dbgOn(){try{return localStorage.getItem("trDebug")==="1"}catch(e){return false}}
function dbgShow(m){const e=document.getElementById("tr-dbg");if(!e)return;if(!dbgOn()){e.hidden=true;return}e.hidden=false;e.textContent="🔍 "+m}
function asrEngineVal(){try{const v=localStorage.getItem("asrEngine");return v==="tiny"||v==="base"?v:"base"}catch(e){return"base"}}
function asrVals(){const g=(k,d)=>{try{const v=parseInt(localStorage.getItem(k),10);return Number.isFinite(v)?v:d}catch(e){return d}};return{sens:g("asrSens",1),minv:g("asrMinV",180),vowel:g("asrVowel",90),end:g("asrEnd",220),max:g("asrMax",4),start:g("asrStart",100)}}
function pushNativePrefs(){volumeLevel=activeVolume();if(!window.TomNative)return;try{const q=asrVals();TomNative.setAsr&&TomNative.setAsr(q.sens,q.minv,q.vowel,q.end,q.max*1000,q.start);TomNative.setAsrEngine&&TomNative.setAsrEngine(asrEngineVal());TomNative.setAutoLearn&&TomNative.setAutoLearn(localStorage.getItem("tplAuto")!=="0");TomNative.setTplStrict&&TomNative.setTplStrict(parseInt(localStorage.getItem("tplStrict")||"1",10));TomNative.setTplWarn&&TomNative.setTplWarn(Math.round(parseFloat(localStorage.getItem("tplWarn")||"1.25")*100));TomNative.setTplModel&&TomNative.setTplModel(localStorage.getItem("tplModel")!=="0");TomNative.setSpeed&&TomNative.setSpeed(speechSpeed);TomNative.setVolume&&TomNative.setVolume(volumeLevel/100);TomNative.setSystemVoice&&TomNative.setSystemVoice(localStorage.getItem("systemVoice")==="1");TomNative.setVoice&&TomNative.setVoice(parseInt(localStorage.getItem("voiceSel")||"9",10));TomNative.setSteps&&TomNative.setSteps(parseInt(localStorage.getItem(STEPS_KEY)||"6",10)||6)}catch(e){}}
/* ---------- مؤثرات القط ---------- */
let ac;function unlockAudio(){try{ac=ac||new(window.AudioContext||window.webkitAudioContext)();if(ac.state==="suspended")ac.resume()}catch(e){}}
function meow(pitch,dur){if(!ac)return;try{const t=ac.currentTime,o=ac.createOscillator(),f=ac.createBiquadFilter(),g=ac.createGain();o.type="sawtooth";f.type="bandpass";f.Q.value=3;
 o.frequency.setValueAtTime(430*pitch,t);o.frequency.linearRampToValueAtTime(780*pitch,t+dur*.35);o.frequency.linearRampToValueAtTime(470*pitch,t+dur);
 f.frequency.setValueAtTime(900,t);f.frequency.linearRampToValueAtTime(1900,t+dur*.4);f.frequency.linearRampToValueAtTime(900,t+dur);
 g.gain.setValueAtTime(.0001,t);g.gain.exponentialRampToValueAtTime(.85*(activeVolume()/100),t+.05);g.gain.exponentialRampToValueAtTime(.0001,t+dur);
 o.connect(f);f.connect(g);g.connect(ac.destination);o.start(t);o.stop(t+dur+.05)}catch(e){}}
function burst(el,emoji,n,cx,cy){const box=el.closest(".cat-card,.mini-bar");if(!box)return;const fx=box.querySelector(".fx");if(!fx)return;const r=fx.getBoundingClientRect();
 for(let i=0;i<n;i++){const b=document.createElement("b");b.textContent=emoji[i%emoji.length];b.style.left=(cx-r.left-12+(Math.random()*50-25))+"px";b.style.top=(cy-r.top-12+(Math.random()*20-10))+"px";b.style.setProperty("--dx",(Math.random()*80-40)+"px");b.style.animationDelay=(i*70)+"ms";fx.appendChild(b);setTimeout(()=>b.remove(),1500)}}
function poke(el,e){
 unlockAudio();wake();
 const r=el.getBoundingClientRect(),x=(e.clientX-r.left)/r.width,y=(e.clientY-r.top)/r.height;
 const now=Date.now();pokes=now-pokeT<2500?pokes+1:1;pokeT=now;
 if(pokes>=6){pokes=0;catOnce("surprise",900);burst(el,["💫","😵","⭐"],3,e.clientX,e.clientY);meow(.7,.7);return}
 if(y<.6){catOnce("happy",900);burst(el,["❤️","💕","✨"],3,e.clientX,e.clientY);meow(1+Math.random()*.25,.5)}
 else if(x>.72&&y>.55){catOnce("swing",1300);meow(.9,.35)}
 else{catOnce("surprise",600);burst(el,["⭐","✨"],2,e.clientX,e.clientY);
  meow(1.35,.3)}
}
function bindCat(el){if(!el)return;el.addEventListener("pointerdown",e=>poke(el,e))}
function look(e){catEls().forEach(c=>{if(!c)return;const r=c.getBoundingClientRect();if(!r.width)return;const dx=e.clientX-(r.left+r.width/2),dy=e.clientY-(r.top+r.height*.4),d=Math.hypot(dx,dy)||1,k=Math.min(1,d/200);
 c.style.setProperty("--px",(dx/d*4*k).toFixed(1)+"px");c.style.setProperty("--py",(dy/d*5*k).toFixed(1)+"px")})}
function startIdle(){
 document.addEventListener("pointermove",look);document.addEventListener("pointerdown",e=>{look(e);wake()});
 setInterval(()=>{const c=$("cat3");if(c.classList.contains("talk")||c.classList.contains("listen")||c.classList.contains("sleep"))return;
  const idle=Date.now()-idleAt;
  if(idle>45000){catSet("sleep",true);const box=$("tr-card");if(box&&!box.querySelector(".zzz")){const z=document.createElement("div");z.className="zzz";z.textContent="💤";box.appendChild(z)}return}
  const a=["earL","earR","yawn","swing"][Math.floor(Math.random()*4)];catOnce(a,a==="swing"?1300:900)
 },5500)}
function ones(n){return ["","وَاحِد","اِثْنَان","ثَلَاثَة","أَرْبَعَة","خَمْسَة","سِتَّة","سَبْعَة","ثَمَانِيَة","تِسْعَة"][n]}
function tens(n){return ["","عَشَرَة","عِشْرُون","ثَلَاثُون","أَرْبَعُون","خَمْسُون","سِتُّون","سَبْعُون","ثَمَانُون","تِسْعُون"][n]}
function numberWord(n){if(n<10)return ones(n);if(n===10)return "عَشَرَة";if(n===11)return "أَحَدَ عَشَر";if(n===12)return "اِثْنَا عَشَر";if(n<20)return ones(n-10)+"َ عَشَر";if(n<100){let u=n%10,t=Math.floor(n/10);return u?tens(t)+" وَ"+ones(u).toLowerCase():tens(t)}if(n===100)return "مِئَة";if(n<200)return "مِئَة وَ"+numberWord(n-100);if(n<1000){let h=Math.floor(n/100),r=n%100,hw=["","","مِئَتَان","ثَلَاثُمِئَة","أَرْبَعُمِئَة","خَمْسُمِئَة","سِتُّمِئَة","سَبْعُمِئَة","ثَمَانِيُمِئَة","تِسْعُمِئَة"][h];return r?hw+" وَ"+numberWord(r):hw}if(n===1000)return "أَلْف";return String(n)}
function renderNumbers(){const g=$("number-grid");g.innerHTML="";for(let n=1;n<=maxNumber;n++){const b=document.createElement("button");b.className="number"+(n===selectedNumber?" selected":"");b.textContent=n;b.onclick=()=>{selectedNumber=n;document.querySelectorAll(".number").forEach(x=>x.classList.remove("selected"));b.classList.add("selected");talk(numberWord(n));};g.appendChild(b)}}
function currentLetter(){return letters[selectedLetter][0]}
const spd=k=>Math.max(.6,Math.min(1.2,speechSpeed*k));
// يحدد النص المنطوق وسرعته لكل عنصر تعليمي؛ نفس الدالة تُستعمل للنطق وللتجهيز المسبق كي يتطابق المخزَّن مع المطلوب
function learnArgs(text){
 const t=String(text||"");
 if(/^[ء-ي][َُِْ]$/.test(t))return["حرف "+t+"، كرر: "+t,spd(.8)];
 if(/^[ء-ي][اوي]$/.test(t))return["مد: "+t,spd(.84)];
 return[t,spd(.91)];
}
function speakLearning(text){const a=learnArgs(text);return talk(a[0],a[1])}
function prefetchAll(){
 if(!window.TomNative||!TomNative.prefetch)return;
 const items=[],seen=new Set(),add=(t,sp)=>{const k=t+"|"+sp;if(!seen.has(k)){seen.add(k);items.push([t,Number(sp.toFixed(3))])}};
 const wordMap={"ب":["بَاب","بَيْت","بَطَّة"],"ر":["رُمَّان","رَأْس","رِيشَة"],"م":["مَاء","مُوز","مَلِك"],"ت":["تِين","تُوت","تَمْر"],"س":["سَمَك","سُوق","سَيْف"],"ن":["نُور","نَار","نَمْل"],"ك":["كِتَاب","كُرَة","كَلْب"]};
 const order=letters.map((_,i)=>i);order.sort((a,b)=>(a===selectedLetter?-1:b===selectedLetter?1:a-b));
 order.forEach(i=>{const l=letters[i][0];
  const la=learnArgs(letters[i][1]);add(la[0],la[1]);
  [l+"َ",l+"ِ",l+"ُ",l+"ْ",l+"ا",l+"و",l+"ي"].forEach(x=>{const a=learnArgs(x);add(a[0],a[1])});
  (wordMap[l]||[l+"َاب",l+"ِي",l+"ُو"]).forEach(x=>{const a=learnArgs(x);add(a[0],a[1])});
 });
 colors.forEach(c=>add(c[0],speechSpeed));
 try{for(let n=1;n<=Math.min(maxNumber,30);n++)add(numberWord(n),speechSpeed)}catch(e){}
 ["مَرْحَبًا أَنَا القِطُّ المُتَكَلِّم"].forEach(x=>add(x,speechSpeed));
 lessonWords.forEach((_,i)=>{["word","both"].forEach(k=>{const a=lsArgs(i,k);add(a[0],a[1])})});
 add("وَضْعُ الدُّرُوس. اِخْتَرْ حَرْفًا",0.78);add(TR_OK,TR_SP);add(TR_BAD,TR_SP);add("وَضْعُ التَّدْرِيب",0.78);
 try{TomNative.prefetch(JSON.stringify(items))}catch(e){}
}
let prefetchT=null;function schedulePrefetch(){clearTimeout(prefetchT);prefetchT=setTimeout(prefetchAll,1800)}
function addMini(parent,text,extra){const b=document.createElement("button");b.className="mini-item"+(extra?" "+extra:"");b.textContent=text;b.onclick=()=>speakLearning(text);parent.appendChild(b);return b}
function renderLetterLessons(){
 const l=currentLetter(), h=$("harakat-grid"),m=$("madd-grid"),s=$("syllable-grid"),w=$("word-grid");h.innerHTML="";m.innerHTML="";s.innerHTML="";w.innerHTML="";
 [[l+"َ","فَتْحَة"],[l+"ِ","كِسْرَة"],[l+"ُ","ضَمَّة"],[l+"ْ","سُكُون"]].forEach(x=>addMini(h,x[0]));
 [[l+"ا","مَدٌّ بِالأَلِف"],[l+"و","مَدٌّ بِالْوَاو"],[l+"ي","مَدٌّ بِالْيَاءِ"]].forEach(x=>addMini(m,x[0]));
 [l+"َ",l+"ِ",l+"ُ",l+"ا",l+"و",l+"ي"].forEach(x=>addMini(s,x));
 const wordMap={"ب":["بَاب","بَيْت","بَطَّة"],"ر":["رُمَّان","رَأْس","رِيشَة"],"م":["مَاء","مُوز","مَلِك"],"ت":["تِين","تُوت","تَمْر"],"س":["سَمَك","سُوق","سَيْف"],"ن":["نُور","نَار","نَمْل"],"ك":["كِتَاب","كُرَة","كَلْب"]};
 (wordMap[l]||[l+"َاب",l+"ِي",l+"ُو"]).forEach(x=>{const b=addMini(w,x);b.classList.add("word-item")});
 $("harakat-grid").parentElement.hidden=!features.harakat;$("madd-grid").parentElement.hidden=!features.madd;$("syllable-grid").parentElement.hidden=!features.madd;$("word-grid").parentElement.hidden=!features.words;$("start-quiz").parentElement.hidden=!features.quiz;
}
function renderLetters(){const g=$("letter-grid");g.innerHTML="";letters.forEach((x,i)=>{const b=document.createElement("button");b.className="letter"+(i===selectedLetter?" selected":"");b.textContent=x[0];b.onclick=()=>{selectedLetter=i;document.querySelectorAll(".letter").forEach(y=>y.classList.remove("selected"));b.classList.add("selected");$("letter-detail").textContent=x[0]+" — "+x[1];renderLetterLessons();speakLearning(x[1]);};g.appendChild(b)});$("letter-detail").textContent=letters[0][0]+" — "+letters[0][1];renderLetterLessons()}
function renderColors(){const g=$("color-grid");g.innerHTML="";colors.forEach((x,i)=>{const b=document.createElement("button");b.className="color-card"+(i===selectedColor?" selected":"");b.innerHTML='<div class="swatch" style="background:'+x[1]+'"></div>'+x[0];b.onclick=()=>{selectedColor=i;document.querySelectorAll(".color-card").forEach(y=>y.classList.remove("selected"));b.classList.add("selected");talk(x[0]);};g.appendChild(b)})}
function startQuiz(){const pool=["بَ","بِ","بُ","بْ","با","بو","بي","رَ","رِ","رُ","را","رو","ري"];quizAnswer=pool[Math.floor(Math.random()*pool.length)];quizActive=true;$("quiz-prompt").textContent="استمع جيدًا ثم اختر ما سمعت.";{const a=learnArgs(quizAnswer);talk(a[0],a[1])};const options=$("quiz-options");options.innerHTML="";const set=[quizAnswer];while(set.length<4){const x=pool[Math.floor(Math.random()*pool.length)];if(!set.includes(x))set.push(x)}set.sort(()=>Math.random()-0.5);set.forEach(x=>{const b=document.createElement("button");b.className="quiz-option";b.textContent=x;b.onclick=()=>{if(!quizActive)return;if(x===quizAnswer){b.classList.add("correct");$("quiz-prompt").textContent="أحسنت! ⭐ إجابة صحيحة";talk("أَحْسَنْتَ",0.72)}else{b.classList.add("wrong");$("quiz-prompt").textContent="حاول مرة أخرى 🔄";talk("حَاوِلْ مَرَّةً أُخْرَى",0.72)}};options.appendChild(b)})}
function speakCurrent(){const t=document.querySelector(".tab.active"),p=t?t.dataset.page:"numbers";if(p==="numbers")talk(numberWord(selectedNumber));else if(p==="letters")speakLearning(letters[selectedLetter][1]);else talk(colors[selectedColor][0])}
// ===== الوضع الثالث: وضع الدروس (حرف + صورة + كلمة) =====
const LS_KEY="lessonSeen";
const lessonWords=[
 ["أَسَد","🦁"],["بَقَرَة","🐄"],["تَمْر","svg:tamr"],["ثَعْلَب","🦊"],["جَمَل","🐪"],["حِصَان","🐎"],["خَرُوف","🐑"],["دُبّ","svg:bear"],["ذُرَة","🌽"],["رُمَّان","svg:rumman"],
 ["زَرَافَة","🦒"],["سَمَكَة","🐟"],["شَمْس","🌞"],["صَارُوخ","🚀"],["ضِفْدَع","🐸"],["طَائِر","🐦"],["ظَرْف","\u2709\uFE0F"],["عِنَب","🍇"],["غَيْمَة","\u2601\uFE0F"],["فِيل","🐘"],
 ["قِطَّة","🐈"],["كَلْب","🐕"],["لَيْمُون","🍋"],["مَوْز","🍌"],["نَحْلَة","🐝"],["هِلَال","🌙"],["وَرْدَة","🌹"],["يَد","✋"]
];
// رسمان للكلمتين اللتين لا يوجد لهما رمز تعبيري (تمر، رمان)
const LS_SVG={
 tamr:"<svg viewBox=\"0 0 120 120\" xmlns=\"http://www.w3.org/2000/svg\" aria-hidden=\"true\"><defs><linearGradient id=\"d1\" x1=\"0\" y1=\"0\" x2=\"1\" y2=\"1\"><stop offset=\"0\" stop-color=\"#c47a3e\"/><stop offset=\".55\" stop-color=\"#7a3d17\"/><stop offset=\"1\" stop-color=\"#3b1a08\"/></linearGradient><linearGradient id=\"d2\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\"><stop offset=\"0\" stop-color=\"#9a5524\"/><stop offset=\"1\" stop-color=\"#4a230d\"/></linearGradient></defs>\n<g transform=\"rotate(-28 42 60)\"><path d=\"M42 12C62 12 74 34 74 62C74 90 62 108 42 108C22 108 10 90 10 62C10 34 22 12 42 12Z\" fill=\"url(#d1)\" stroke=\"#2e1406\" stroke-width=\"3\"/><path d=\"M26 34Q38 26 50 34M22 54Q40 46 60 54M22 74Q40 66 60 74\" fill=\"none\" stroke=\"#2e1406\" stroke-opacity=\".35\" stroke-width=\"2.5\" stroke-linecap=\"round\"/><ellipse cx=\"28\" cy=\"42\" rx=\"4.5\" ry=\"14\" fill=\"#fff\" fill-opacity=\".35\"/><path d=\"M42 12Q40 5 46 2\" fill=\"none\" stroke=\"#3f7a2a\" stroke-width=\"4\" stroke-linecap=\"round\"/></g>\n<g transform=\"rotate(24 92 78)\"><path d=\"M92 40C108 40 116 56 116 78C116 100 108 116 92 116C76 116 68 100 68 78C68 56 76 40 92 40Z\" fill=\"url(#d2)\" stroke=\"#2e1406\" stroke-width=\"3\"/><path d=\"M92 50C102 50 108 62 108 78C108 94 102 106 92 106C82 106 76 94 76 78C76 62 82 50 92 50Z\" fill=\"#e7b46f\"/><ellipse cx=\"92\" cy=\"78\" rx=\"7\" ry=\"17\" fill=\"#f6e3c0\" stroke=\"#b07a3c\" stroke-width=\"2\"/><path d=\"M92 64V92\" stroke=\"#b07a3c\" stroke-width=\"1.8\"/></g></svg>",
 rumman:"<svg viewBox=\"0 0 120 120\" xmlns=\"http://www.w3.org/2000/svg\" aria-hidden=\"true\"><defs><radialGradient id=\"rr\" cx=\".4\" cy=\".3\" r=\".9\"><stop offset=\"0\" stop-color=\"#ee5a4f\"/><stop offset=\".7\" stop-color=\"#b8182a\"/><stop offset=\"1\" stop-color=\"#7d0f1d\"/></radialGradient></defs>\n<path d=\"M44 20L50 6L57 17L64 4L71 17L78 6L84 20Z\" fill=\"#9c1426\" stroke=\"#6e0b17\" stroke-width=\"2\" stroke-linejoin=\"round\"/>\n<circle cx=\"60\" cy=\"66\" r=\"46\" fill=\"url(#rr)\" stroke=\"#6e0b17\" stroke-width=\"3\"/>\n<circle cx=\"60\" cy=\"66\" r=\"40\" fill=\"#fff0cf\"/>\n<path d=\"M60 26V106M20 66H100M32 38L88 94M88 38L32 94\" stroke=\"#f1d6a0\" stroke-width=\"3\"/>\n<ellipse cx=\"90.0\" cy=\"66.0\" rx=\"6.2\" ry=\"7.4\" transform=\"rotate(90 90.0 66.0)\" fill=\"#c4122b\" stroke=\"#7a0a1a\" stroke-width=\"1.3\"/><ellipse cx=\"88.5\" cy=\"63.8\" rx=\"1.8\" ry=\"2.6\" fill=\"#fff\" fill-opacity=\".55\"/><ellipse cx=\"86.0\" cy=\"81.0\" rx=\"6.2\" ry=\"7.4\" transform=\"rotate(120 86.0 81.0)\" fill=\"#c4122b\" stroke=\"#7a0a1a\" stroke-width=\"1.3\"/><ellipse cx=\"84.5\" cy=\"78.8\" rx=\"1.8\" ry=\"2.6\" fill=\"#fff\" fill-opacity=\".55\"/><ellipse cx=\"75.0\" cy=\"92.0\" rx=\"6.2\" ry=\"7.4\" transform=\"rotate(150 75.0 92.0)\" fill=\"#c4122b\" stroke=\"#7a0a1a\" stroke-width=\"1.3\"/><ellipse cx=\"73.5\" cy=\"89.8\" rx=\"1.8\" ry=\"2.6\" fill=\"#fff\" fill-opacity=\".55\"/><ellipse cx=\"60.0\" cy=\"96.0\" rx=\"6.2\" ry=\"7.4\" transform=\"rotate(180 60.0 96.0)\" fill=\"#c4122b\" stroke=\"#7a0a1a\" stroke-width=\"1.3\"/><ellipse cx=\"58.5\" cy=\"93.8\" rx=\"1.8\" ry=\"2.6\" fill=\"#fff\" fill-opacity=\".55\"/><ellipse cx=\"45.0\" cy=\"92.0\" rx=\"6.2\" ry=\"7.4\" transform=\"rotate(210 45.0 92.0)\" fill=\"#c4122b\" stroke=\"#7a0a1a\" stroke-width=\"1.3\"/><ellipse cx=\"43.5\" cy=\"89.8\" rx=\"1.8\" ry=\"2.6\" fill=\"#fff\" fill-opacity=\".55\"/><ellipse cx=\"34.0\" cy=\"81.0\" rx=\"6.2\" ry=\"7.4\" transform=\"rotate(240 34.0 81.0)\" fill=\"#c4122b\" stroke=\"#7a0a1a\" stroke-width=\"1.3\"/><ellipse cx=\"32.5\" cy=\"78.8\" rx=\"1.8\" ry=\"2.6\" fill=\"#fff\" fill-opacity=\".55\"/><ellipse cx=\"30.0\" cy=\"66.0\" rx=\"6.2\" ry=\"7.4\" transform=\"rotate(270 30.0 66.0)\" fill=\"#c4122b\" stroke=\"#7a0a1a\" stroke-width=\"1.3\"/><ellipse cx=\"28.5\" cy=\"63.8\" rx=\"1.8\" ry=\"2.6\" fill=\"#fff\" fill-opacity=\".55\"/><ellipse cx=\"34.0\" cy=\"51.0\" rx=\"6.2\" ry=\"7.4\" transform=\"rotate(300 34.0 51.0)\" fill=\"#c4122b\" stroke=\"#7a0a1a\" stroke-width=\"1.3\"/><ellipse cx=\"32.5\" cy=\"48.8\" rx=\"1.8\" ry=\"2.6\" fill=\"#fff\" fill-opacity=\".55\"/><ellipse cx=\"45.0\" cy=\"40.0\" rx=\"6.2\" ry=\"7.4\" transform=\"rotate(330 45.0 40.0)\" fill=\"#c4122b\" stroke=\"#7a0a1a\" stroke-width=\"1.3\"/><ellipse cx=\"43.5\" cy=\"37.8\" rx=\"1.8\" ry=\"2.6\" fill=\"#fff\" fill-opacity=\".55\"/><ellipse cx=\"60.0\" cy=\"36.0\" rx=\"6.2\" ry=\"7.4\" transform=\"rotate(360 60.0 36.0)\" fill=\"#c4122b\" stroke=\"#7a0a1a\" stroke-width=\"1.3\"/><ellipse cx=\"58.5\" cy=\"33.8\" rx=\"1.8\" ry=\"2.6\" fill=\"#fff\" fill-opacity=\".55\"/><ellipse cx=\"75.0\" cy=\"40.0\" rx=\"6.2\" ry=\"7.4\" transform=\"rotate(390 75.0 40.0)\" fill=\"#c4122b\" stroke=\"#7a0a1a\" stroke-width=\"1.3\"/><ellipse cx=\"73.5\" cy=\"37.8\" rx=\"1.8\" ry=\"2.6\" fill=\"#fff\" fill-opacity=\".55\"/><ellipse cx=\"86.0\" cy=\"51.0\" rx=\"6.2\" ry=\"7.4\" transform=\"rotate(420 86.0 51.0)\" fill=\"#c4122b\" stroke=\"#7a0a1a\" stroke-width=\"1.3\"/><ellipse cx=\"84.5\" cy=\"48.8\" rx=\"1.8\" ry=\"2.6\" fill=\"#fff\" fill-opacity=\".55\"/><ellipse cx=\"77.5\" cy=\"73.4\" rx=\"6.2\" ry=\"7.4\" transform=\"rotate(113 77.5 73.4)\" fill=\"#c4122b\" stroke=\"#7a0a1a\" stroke-width=\"1.3\"/><ellipse cx=\"76.0\" cy=\"71.2\" rx=\"1.8\" ry=\"2.6\" fill=\"#fff\" fill-opacity=\".55\"/><ellipse cx=\"67.1\" cy=\"83.6\" rx=\"6.2\" ry=\"7.4\" transform=\"rotate(158 67.1 83.6)\" fill=\"#c4122b\" stroke=\"#7a0a1a\" stroke-width=\"1.3\"/><ellipse cx=\"65.6\" cy=\"81.4\" rx=\"1.8\" ry=\"2.6\" fill=\"#fff\" fill-opacity=\".55\"/><ellipse cx=\"52.6\" cy=\"83.5\" rx=\"6.2\" ry=\"7.4\" transform=\"rotate(203 52.6 83.5)\" fill=\"#c4122b\" stroke=\"#7a0a1a\" stroke-width=\"1.3\"/><ellipse cx=\"51.1\" cy=\"81.3\" rx=\"1.8\" ry=\"2.6\" fill=\"#fff\" fill-opacity=\".55\"/><ellipse cx=\"42.4\" cy=\"73.1\" rx=\"6.2\" ry=\"7.4\" transform=\"rotate(248 42.4 73.1)\" fill=\"#c4122b\" stroke=\"#7a0a1a\" stroke-width=\"1.3\"/><ellipse cx=\"40.9\" cy=\"70.9\" rx=\"1.8\" ry=\"2.6\" fill=\"#fff\" fill-opacity=\".55\"/><ellipse cx=\"42.5\" cy=\"58.6\" rx=\"6.2\" ry=\"7.4\" transform=\"rotate(293 42.5 58.6)\" fill=\"#c4122b\" stroke=\"#7a0a1a\" stroke-width=\"1.3\"/><ellipse cx=\"41.0\" cy=\"56.4\" rx=\"1.8\" ry=\"2.6\" fill=\"#fff\" fill-opacity=\".55\"/><ellipse cx=\"52.9\" cy=\"48.4\" rx=\"6.2\" ry=\"7.4\" transform=\"rotate(338 52.9 48.4)\" fill=\"#c4122b\" stroke=\"#7a0a1a\" stroke-width=\"1.3\"/><ellipse cx=\"51.4\" cy=\"46.2\" rx=\"1.8\" ry=\"2.6\" fill=\"#fff\" fill-opacity=\".55\"/><ellipse cx=\"67.4\" cy=\"48.5\" rx=\"6.2\" ry=\"7.4\" transform=\"rotate(383 67.4 48.5)\" fill=\"#c4122b\" stroke=\"#7a0a1a\" stroke-width=\"1.3\"/><ellipse cx=\"65.9\" cy=\"46.3\" rx=\"1.8\" ry=\"2.6\" fill=\"#fff\" fill-opacity=\".55\"/><ellipse cx=\"77.6\" cy=\"58.9\" rx=\"6.2\" ry=\"7.4\" transform=\"rotate(428 77.6 58.9)\" fill=\"#c4122b\" stroke=\"#7a0a1a\" stroke-width=\"1.3\"/><ellipse cx=\"76.1\" cy=\"56.7\" rx=\"1.8\" ry=\"2.6\" fill=\"#fff\" fill-opacity=\".55\"/><ellipse cx=\"65.6\" cy=\"71.7\" rx=\"6.2\" ry=\"7.4\" transform=\"rotate(136 65.6 71.7)\" fill=\"#c4122b\" stroke=\"#7a0a1a\" stroke-width=\"1.3\"/><ellipse cx=\"64.1\" cy=\"69.5\" rx=\"1.8\" ry=\"2.6\" fill=\"#fff\" fill-opacity=\".55\"/><ellipse cx=\"52.2\" cy=\"68.0\" rx=\"6.2\" ry=\"7.4\" transform=\"rotate(256 52.2 68.0)\" fill=\"#c4122b\" stroke=\"#7a0a1a\" stroke-width=\"1.3\"/><ellipse cx=\"50.7\" cy=\"65.8\" rx=\"1.8\" ry=\"2.6\" fill=\"#fff\" fill-opacity=\".55\"/><ellipse cx=\"62.2\" cy=\"58.3\" rx=\"6.2\" ry=\"7.4\" transform=\"rotate(376 62.2 58.3)\" fill=\"#c4122b\" stroke=\"#7a0a1a\" stroke-width=\"1.3\"/><ellipse cx=\"60.7\" cy=\"56.1\" rx=\"1.8\" ry=\"2.6\" fill=\"#fff\" fill-opacity=\".55\"/></svg>",
 bear:"<svg viewBox=\"0 0 120 120\" xmlns=\"http://www.w3.org/2000/svg\" aria-hidden=\"true\"><g fill=\"#8a5a33\" stroke=\"#4e2f16\" stroke-width=\"3\"><circle cx=\"38\" cy=\"22\" r=\"11\"/><circle cx=\"82\" cy=\"22\" r=\"11\"/><ellipse cx=\"26\" cy=\"68\" rx=\"11\" ry=\"20\" transform=\"rotate(18 26 68)\"/><ellipse cx=\"94\" cy=\"68\" rx=\"11\" ry=\"20\" transform=\"rotate(-18 94 68)\"/><ellipse cx=\"60\" cy=\"76\" rx=\"30\" ry=\"32\"/><ellipse cx=\"42\" cy=\"108\" rx=\"15\" ry=\"10\"/><ellipse cx=\"78\" cy=\"108\" rx=\"15\" ry=\"10\"/><circle cx=\"60\" cy=\"40\" r=\"26\"/></g><circle cx=\"38\" cy=\"22\" r=\"5\" fill=\"#e8a98a\"/><circle cx=\"82\" cy=\"22\" r=\"5\" fill=\"#e8a98a\"/><ellipse cx=\"60\" cy=\"80\" rx=\"18\" ry=\"22\" fill=\"#e8c9a0\"/><ellipse cx=\"60\" cy=\"48\" rx=\"13\" ry=\"10\" fill=\"#e8c9a0\"/><circle cx=\"50\" cy=\"36\" r=\"3.5\" fill=\"#222\"/><circle cx=\"70\" cy=\"36\" r=\"3.5\" fill=\"#222\"/><ellipse cx=\"60\" cy=\"44\" rx=\"5\" ry=\"3.5\" fill=\"#222\"/><path d=\"M60 47V51M54 52Q60 56 66 52\" stroke=\"#222\" stroke-width=\"2\" fill=\"none\" stroke-linecap=\"round\"/><ellipse cx=\"42\" cy=\"108\" rx=\"8\" ry=\"5\" fill=\"#e8c9a0\"/><ellipse cx=\"78\" cy=\"108\" rx=\"8\" ry=\"5\" fill=\"#e8c9a0\"/></svg>"
};
const LS_COLORS=["#ffe3b3","#ffd6e0","#d6f0ff","#dff5d2","#ead9ff","#fff0a8","#ffd9c2"];
let lsIdx=0;
const lsSeen=()=>{try{return JSON.parse(localStorage.getItem(LS_KEY)||"[]")}catch(e){return[]}};
function lsMark(i){try{const s=lsSeen();if(s.indexOf(i)<0){s.push(i);localStorage.setItem(LS_KEY,JSON.stringify(s))}}catch(e){}}
const lsPic=p=>p.indexOf("svg:")===0?LS_SVG[p.slice(4)]:'<span class="ls-emoji">'+p+'</span>';
function lsWordHtml(w){const m=w.match(/^([\s\S][\u064B-\u0652\u0670]*)([\s\S]*)$/);return"<b>"+m[1]+"</b>"+m[2]}
// نفس صياغة النطق تُستعمل للنطق وللتجهيز المسبق فيتطابق المخزَّن مع المطلوب
const LS_SAY={"نَحْلَة":"نَحْلَه"};
function lsArgs(i,kind){const n=letters[i][1],w=LS_SAY[lessonWords[i][0]]||lessonWords[i][0];return learnArgs(kind==="word"?w:kind==="name"?n:n+"، "+w)}
function lsSay(kind){const a=lsArgs(lsIdx,kind);talk(a[0],a[1])}
function lsGrid(){
 const g=$("ls-grid");
 if(!g.children.length){
  letters.forEach((l,i)=>{const b=document.createElement("button");b.className="ls-tile";b.type="button";b.setAttribute("aria-label",l[1]+" "+lessonWords[i][0]);
   b.innerHTML='<span class="ls-t-l">'+l[0]+'</span><span class="ls-t-p">'+lsPic(lessonWords[i][1])+'</span>';
   b.onclick=()=>lsOpen(i,true);g.appendChild(b)});
 }
 const seen=lsSeen();
 Array.prototype.forEach.call(g.children,(b,i)=>{b.classList.toggle("active",i===lsIdx);b.classList.toggle("seen",seen.indexOf(i)>=0)});
 $("ls-progress").textContent="تعلّمتَ "+seen.length+" من "+letters.length+" حرفًا";
}
function lsOpen(i,say){
 lsIdx=(i+letters.length)%letters.length;
 const L=letters[lsIdx],W=lessonWords[lsIdx];
 $("ls-card").style.setProperty("--ls",LS_COLORS[lsIdx%LS_COLORS.length]);
 $("ls-letter").textContent=L[0];$("ls-name").textContent=L[1];
 $("ls-pic").innerHTML=lsPic(W[1]);$("ls-word").innerHTML=lsWordHtml(W[0]);
 $("ls-count").textContent="الحرف "+(lsIdx+1)+" من "+letters.length;
 lsMark(lsIdx);lsGrid();
 if(say)lsSay("both");
}
(function lsInit(){
 $("lessons-mode").onclick=()=>setMode("lessons");$("training-mode").onclick=()=>setMode("training");
 $("ls-prev").onclick=()=>lsOpen(lsIdx-1,true);
 $("ls-next").onclick=()=>lsOpen(lsIdx+1,true);
 $("ls-letter").onclick=()=>lsSay("name");
 $("ls-pic").onclick=()=>lsSay("word");
 $("ls-word").onclick=()=>lsSay("word");
 $("ls-play").onclick=()=>lsSay("both");
 lsOpen(0,false);
})();


// ===== وضع التدريب: القط يسمع الطفل ويقول صحيح أو خطأ، ويتجاهل بصمت كل ما ليس رقمًا أو حرفًا أو لونًا =====
const TR_OK="أَحْسَنْتَ",TR_BAD="حَاوِلْ مَرَّةً أُخْرَى",TR_SP=0.9;
let trOn=false,trCat="all",trTarget=null,trScore=0,trBags={n:[],l:[],c:[]},trLast=null,trTimer=0,trLock=false,trBuilt=false,trL=new Map(),trC=new Map(),trN=new Map(),trBase=new Set();
try{trLast=localStorage.getItem("trLast")}catch(e){}
let trAfter=null,trSpoke=false;
function trAfterSet(f,d,fb){trAfter={f:f,d:d};trSpoke=false;clearTimeout(trTimer);trTimer=setTimeout(()=>{muteMic(0);const a=trAfter;trAfter=null;if(a)a.f()},fb)}
function trBusy(on){try{if(window.TomNative&&TomNative.setBusy)TomNative.setBusy(on)}catch(e){}}
function muteMic(ms){try{if(window.TomNative&&TomNative.muteMic)TomNative.muteMic(ms)}catch(e){}}
const trKey=it=>it.t+":"+it.i;
function trShuffle(a){for(let i=a.length-1;i>0;i--){const j=Math.floor(Math.random()*(i+1));const x=a[i];a[i]=a[j];a[j]=x}return a}
// حقيبة عشوائية: كل عنصر يظهر مرة واحدة قبل أن يتكرر أي عنصر، وترتيبها يتغير كل مرة، ولا يتكرر آخر عنصر عند إعادة الخلط
function trRefill(t){const a=[];if(t==="n"){for(let n=1;n<=Math.min(maxNumber,999);n++)a.push({t:"n",i:n})}else if(t==="l"){letters.forEach((_,i)=>a.push({t:"l",i:i}))}else{colors.forEach((_,i)=>a.push({t:"c",i:i}))}
 trShuffle(a);if(a.length>1&&trLast&&trKey(a[a.length-1])===trLast){const k=Math.floor(Math.random()*(a.length-1));const x=a[a.length-1];a[a.length-1]=a[k];a[k]=x}trBags[t]=a}
function trNext(){const map={numbers:"n",letters:"l",colors:"c"},types=trCat==="all"?["n","l","c"]:[map[trCat]];const t=types[Math.floor(Math.random()*types.length)];
 if(!trBags[t].length)trRefill(t);const it=trBags[t].pop();trLast=trKey(it);try{localStorage.setItem("trLast",trLast)}catch(e){}trTarget=it;trTries=0;trShow(it)}
function trShow(it){try{window.TomNative&&TomNative.setTarget&&TomNative.setTarget(trKey(it))}catch(e){}const el=$("tr-target");el.className="tr-target pop";let h;
 if(it.t==="n")h=String(it.i);else if(it.t==="l")h=letters[it.i][0];else h='<span class="tr-sw" style="background:'+colors[it.i][1]+'"></span>';
 el.innerHTML=h;el.style.fontSize=(it.t==="n"&&String(it.i).length>3)?"56px":"";if(trOn)$("tr-status").textContent="قُلْ ما تراه 🎤"}
function trSay(it){return it.t==="n"?numberWord(it.i):it.t==="l"?letters[it.i][1]:colors[it.i][0]}
// ---- مطابقة الكلام ----
const TR_FILL=new Set(["العدد","رقم","الرقم","عدد","الحرف","حرف","اللون","لون","هذا","هذه","انه","انها"]);
const nrm=s=>String(s||"").replace(/[٠-٩]/g,d=>"٠١٢٣٤٥٦٧٨٩".indexOf(d)).replace(/[\u064B-\u065F\u0670\u0640]/g,"").replace(/[أإآٱ]/g,"ا").replace(/ى/g,"ي").replace(/ة/g,"ه").replace(/ائ/g,"ئ").replace(/ؤ/g,"و").replace(/ئ/g,"ي").replace(/ء/g,"").replace(/[^\u0621-\u064A0-9\s]/g," ").replace(/\s+/g," ").trim();
const sh=s=>s.length>2?s.replace(/ه$/,""):s;
const numTok=w=>sh(w).replace(/(ان|ين|ون)$/,"ن");
function trBuild(){
 trL=new Map();trC=new Map();trN=new Map();trBase=new Set();
 letters.forEach((l,i)=>{[nrm(l[1]),nrm(l[0]),i===0?"":nrm(l[0])+"ا"].forEach(a=>{if(a)trL.set(a,i)})});
 colors.forEach((c,i)=>{trC.set(sh(nrm(c[0])),i)});
 const fem={"احمر":"حمرا","ازرق":"زرقا","اصفر":"صفرا","اخضر":"خضرا","اسود":"سودا","ابيض":"بيضا"};
 colors.forEach((c,i)=>{const b=sh(nrm(c[0]));if(fem[b])trC.set(fem[b],i)});
 const words=[];for(let n=1;n<=999;n++){const t=nrm(numberWord(n)).split(" ").filter(Boolean);words.push(t);trBase.add(numTok(t[0]))}
 words.forEach((t,k)=>{const toks=t.map(x=>{x=numTok(x);if(!trBase.has(x)&&x[0]==="و"&&trBase.has(x.slice(1)))x=x.slice(1);return x});trN.set(toks.slice().sort().join(" "),k+1)});
 trN.delete("الف");trBuilt=true}
function trNumFromWords(toks){
 const ws=toks.map(x=>{x=numTok(x);if(!trBase.has(x)&&x[0]==="و"&&trBase.has(x.slice(1)))x=x.slice(1);return x}).filter(x=>trBase.has(x));
 if(!ws.length)return null;const v=trN.get(ws.slice().sort().join(" "));return v==null?null:v}
function trParse(text){
 if(!trBuilt)trBuild();
 let t=nrm(text).split(" ").filter(w=>w&&!TR_FILL.has(w));t=t.filter((w,i)=>i===0||w!==t[i-1]);
 if(!t.length||t.length>4)return[];            // كلام طويل = ليس تدريبًا، نتجاهله
 const found=[],seen=new Set(),add=it=>{const k=trKey(it);if(!seen.has(k)){seen.add(k);found.push(it)}};
 const digs=t.filter(w=>/^\d+$/.test(w));
 if(digs.length===1&&t.length===1){const n=parseInt(digs[0],10);if(n>=1&&n<=999)add({t:"n",i:n})}
 else if(!digs.length){const n=trNumFromWords(t);if(n!=null)add({t:"n",i:n})}
 t.forEach(w=>{const a=w.replace(/^ال(?=.{2,})/,"");[w,a].forEach(x=>{
  const li=trL.get(x);if(li!=null&&(x.length>2||t.length===1))add({t:"l",i:li});
  const ci=trC.get(sh(x));if(ci!=null)add({t:"c",i:ci})})});
 return found}
// أزواج الحروف التي يُقبل أحدها مكان الآخر (لهجات). الأصل صارم: كل حرف له صيغه فقط. أضف زوجًا هنا عند الحاجة، مثل "قك".
const TR_CONF=["ظض"];
const trGroup=new Map();TR_CONF.forEach((g,gi)=>{[...g].forEach(ch=>{if(!trGroup.has(ch))trGroup.set(ch,new Set());trGroup.get(ch).add(gi)})});
function trSimilar(a,b){const x=trGroup.get(letters[a][0]),y=trGroup.get(letters[b][0]);if(!x||!y)return false;for(const g of x)if(y.has(g))return true;return false}
let trTries=0,trTails=null,trFirst=null,trMax=3,trHelp=true;
try{const m=localStorage.getItem("trMax");if(m!==null)trMax=Math.max(0,parseInt(m,10)||0);trHelp=localStorage.getItem("trHelp")!=="0"}catch(e){}
// مطابقة مرنة لأسماء الحروف: الحرف الأول + أي نهاية معروفة من أسماء الحروف (ظا، ظاء، ظاد، ضاء…). ضعيفة: تُقبل صحيحةً فقط ولا تُحتسب خطأً أبدًا.
function trFuzzy(text){
 if(!trTails){trTails=new Set(["","ا","او","اي","ي","و","اه"]);trFirst=new Map();
  letters.forEach((l,i)=>{const n=nrm(l[1]);if(n.length>1)trTails.add(n.slice(1));const c=nrm(l[0]);if(c&&!trFirst.has(c))trFirst.set(c,i)})}
 const t=nrm(text).split(" ").filter(w=>w&&!TR_FILL.has(w));
 if(t.length!==1)return[];
 const w=t[0].replace(/^ال(?=.{2,})/,"");
 if(w.length>5||w.length<1)return[];
 const i=trFirst.get(w[0]);
 return(i!=null&&trTails.has(w.slice(1)))?[i]:[]}
// ---- مطابقة صوتية متساهلة للهدف الحالي فقط (Whisper يخطئ في ع وذ وز ونحوها عند الأطفال) ----
// تُجرَّب فقط حين لا تجد المطابقة الصارمة أي رقم أو حرف أو لون في كلام الطفل، وتقبل الإجابة الصحيحة فقط ولا تُحتسب خطأً أبدًا.
// نقارن «هيكل الحروف الساكنة» (بحذف ا و ي ع ه) ونعطي نصف عقوبة للحروف المتقاربة في النطق.
const TR_NEAR=new Set("سص سث سز زذ زظ ذد ذث ظض دض تط تد كق خغ خك خه حه".split(" ").flatMap(p=>[p,p[1]+p[0]]));
const trSk=s=>nrm(s).split(" ").filter(w=>w&&!TR_FILL.has(w)).map(w=>w.replace(/^ال(?=.{3,})/,"")).join("").replace(/[اويعه]/g,"");
function trWd(a,b){const m=a.length,n=b.length;let p=[];for(let j=0;j<=n;j++)p[j]=j;
 for(let i=1;i<=m;i++){const c=[i];for(let j=1;j<=n;j++){const x=a[i-1],y=b[j-1];const sub=x===y?0:(TR_NEAR.has(x+y)?.5:1);c[j]=Math.min(p[j]+1,c[j-1]+1,p[j-1]+sub)}p=c}return p[n]}
function trLoose(text,target){
 const toks=nrm(text).split(" ").filter(w=>w&&!TR_FILL.has(w));
 if(!toks.length||toks.length>3||toks.some(w=>/\d/.test(w)))return false;
 const heard=trSk(text);if(!heard||heard.length>12)return false;
 const want=trSk(trSay(target));if(want.length<2)return false;
 const L=want.length,thr=L<=2?.5:L<=5?1:L<=8?1.5:2;
 return trWd(heard,want)<=thr}
function trLearn(){try{window.TomNative&&TomNative.confirmLast&&TomNative.confirmLast(trKey(trTarget))}catch(e){}}
function trHandle(text,forced){
 if(!trOn||!trTarget||trLock)return;
 const f=forced||trParse(text);               // لا رقم ولا حرف ولا لون: صمت تام بلا أي رد (يُفحص بعد المطابقة المرنة)
 const weak=(!forced&&trTarget.t==="l")?trFuzzy(text):[];
 const lo=!forced&&!f.length&&!weak.some(i=>i===trTarget.i||trSimilar(i,trTarget.i))&&trLoose(text,trTarget);
 if(!f.length&&!lo&&!weak.some(i=>i===trTarget.i||trSimilar(i,trTarget.i))){muteMic(0);if(!trLock)$("tr-status").textContent="قُلْ ما تراه 🎤";return}
 const ok=lo||f.some(x=>trKey(x)===trKey(trTarget)||(trTarget.t==="l"&&x.t==="l"&&trSimilar(x.i,trTarget.i)))||weak.some(i=>i===trTarget.i||trSimilar(i,trTarget.i)),el=$("tr-target"),cat=$("cat3"),r=cat.getBoundingClientRect();
 trLock=true;muteMic(15000);clearTimeout(trTimer);
 if(ok){if(!forced)trLearn();trScore++;$("tr-score").textContent="⭐ "+trScore;el.classList.add("ok");$("tr-status").textContent="صحيح! أحسنت 🎉";talk(TR_OK,TR_SP);catOnce("happy",1300);burst(cat,["⭐","🎉","✨","💖"],6,r.left+r.width/2,r.top+r.height*.35);
  trAfterSet(()=>{trLock=false;if(trOn)trNext()},80,2200)}
 else{trTries++;el.classList.remove("bad");void el.offsetWidth;el.classList.add("bad");const unlock=()=>{trLock=false;el.classList.remove("bad");if(trOn)$("tr-status").textContent="قُلْ ما تراه 🎤"};
  if(trMax>0&&trTries>=trMax){$("tr-status").textContent="لا بأس، هذه الإجابة، نكمل 💛";const it=trTarget;if(it.t==="l")speakLearning(letters[it.i][1]);else talk(trSay(it));trAfterSet(()=>{trLock=false;if(trOn)trNext()},80,2600)}
  else if(trHelp&&trTries===2){$("tr-status").textContent="استمع ثم قُلْه معي 🙂";const it=trTarget;if(it.t==="l")speakLearning(letters[it.i][1]);else talk(trSay(it));catOnce("surprise",800);trAfterSet(unlock,0,2200)}
  else{$("tr-status").textContent="حاول مرة أخرى 🙂";talk(TR_BAD,TR_SP);catOnce("surprise",800);trAfterSet(unlock,0,1800)}}}
function trStart(){
 unlockAudio();wake();
 if(!window.TomNative||!TomNative.startTraining){$("tr-status").textContent="التدريب بالصوت يعمل داخل التطبيق فقط.";return}
 trOn=true;trBusy(true);trScore=0;$("tr-score").textContent="⭐ 0";trBags={n:[],l:[],c:[]};trBuild();trLock=false;
 $("tr-start").textContent="⏹️ إيقاف التدريب";$("tr-card").classList.remove("idle");trNext();$("tr-status").textContent="جارٍ تجهيز الاستماع…";TomNative.startTraining()}
function trStop(fromNative,msg){
 const was=trOn;trOn=false;trBusy(false);trAfter=null;clearTimeout(trTimer);trLock=false;
 if(!fromNative&&window.TomNative&&TomNative.stopTraining)TomNative.stopTraining();
 $("tr-start").textContent="🎤 ابدأ التدريب";$("tr-card").classList.remove("listening");
 if(was||msg)$("tr-status").textContent=msg||("انتهى التدريب. نتيجتك ⭐ "+trScore)}
(function trInit(){
 
 document.querySelectorAll(".tr-cat").forEach(b=>b.onclick=()=>{document.querySelectorAll(".tr-cat").forEach(x=>x.classList.remove("active"));b.classList.add("active");trCat=b.dataset.cat;trBags={n:[],l:[],c:[]};if(trOn&&!trLock)trNext()});
 $("tr-start").onclick=()=>{if(trOn)trStop(false);else trStart()};
 $("tr-skip").onclick=()=>{if(trOn&&!trLock)trNext()};
 $("tr-hint").onclick=()=>{if(trOn&&trTarget&&!trLock){muteMic(8000);const it=trTarget;if(it.t==="l")speakLearning(letters[it.i][1]);else talk(trSay(it))}};
})();

function setMode(mode,silent){const study=mode==="learning",letterMode=mode==="lessons",train=mode==="training";
 ["learning","lessons","training"].forEach(m=>{$(m).classList.toggle("active",m===mode);$(m+"-mode").classList.toggle("active",m===mode)});
 $("subtitle").textContent=letterMode?"نتعلّم الحروف مع الصور":train?"قُلْ ما تراه وسيصحّح لك القط":"اضغط على القط ليكرر الدرس";
 if(!train&&trOn)trStop(false);
 pushNativePrefs();
 if(silent)return;
 if(study)talk("وَضْعُ التَّعَلُّم. اِضْغَطْ عَلَيَّ لِأُكَرِّرَ",0.78);if(letterMode)talk("وَضْعُ الدُّرُوس. اِخْتَرْ حَرْفًا",0.78);if(train)talk("وَضْعُ التَّدْرِيب",0.78)}
function asrFill(){const q=asrVals();$("asr-engine").value=asrEngineVal();$("asr-sens").value=String(q.sens);$("asr-minv").value=q.minv;$("asr-vowel").value=String(q.vowel);$("asr-end").value=q.end;$("asr-max").value=q.max;$("asr-start").value=q.start;asrLabels()}
function asrLabels(){$("asr-minv-value").textContent=$("asr-minv").value+" م.ث";$("asr-end-value").textContent=$("asr-end").value+" م.ث";$("asr-max-value").textContent=$("asr-max").value+" ث";$("asr-start-value").textContent=$("asr-start").value+" م.ث"}
function openSettings(){$("settings").hidden=false;asrFill();try{tplRefresh()}catch(e){}$("tr-max").value=String(trMax);$("tr-help").checked=trHelp;$("max-number").value=maxNumber;$("system-voice").checked=localStorage.getItem("systemVoice")==="1";$("voice-id").value=localStorage.getItem("voiceSel")||"9";$("tts-quality").value=localStorage.getItem(STEPS_KEY)||"6";$("speech-speed-value").textContent=Number(speechSpeed).toFixed(2);$("speech-speed").value=speechSpeed;$("learning-volume-level").value=learningVolume;$("learning-volume-value").textContent=learningVolume+"%";$("enable-harakat").checked=features.harakat;$("enable-madd").checked=features.madd;$("enable-words").checked=features.words;$("enable-quiz").checked=features.quiz}
function bind(){
 $("learning-mode").onclick=()=>setMode("learning");$("mini-cat-btn").onclick=speakCurrent;
 document.querySelectorAll(".tab").forEach(t=>t.onclick=()=>{document.querySelectorAll(".tab").forEach(x=>x.classList.remove("active"));document.querySelectorAll(".page").forEach(x=>x.classList.remove("active"));t.classList.add("active");$(t.dataset.page).classList.add("active")});
 $("speak-number").onclick=()=>talk(numberWord(selectedNumber));$("speak-letter").onclick=()=>speakLearning(letters[selectedLetter][1]);$("speak-color").onclick=()=>talk(colors[selectedColor][0]);$("start-quiz").onclick=startQuiz;
 $("gear").onclick=()=>askAdult(openSettings);$("gate-ok").onclick=gateSubmit;$("gate-cancel").onclick=gateClose;$("gate-in").onkeydown=e=>{if(e.key==="Enter")gateSubmit()};$("pin-save").onclick=pinSave;$("pin-clear").onclick=pinClear;$("close-settings").onclick=()=>$("settings").hidden=true;
 $("settings-normal").onclick=()=>switchSettings("normal");$("settings-advanced").onclick=()=>switchSettings("advanced");$("settings-training").onclick=()=>switchSettings("training");  $("learning-volume-level").oninput=()=>{learningVolume=Number($("learning-volume-level").value);$("learning-volume-value").textContent=learningVolume+"%";localStorage.setItem(LEARNING_VOLUME_KEY,String(learningVolume));pushNativePrefs();};
 $("save-settings").onclick=()=>{let v=parseInt($("max-number").value,10);if(!Number.isFinite(v))v=20;v=Math.max(1,Math.min(1000,v));maxNumber=v;localStorage.setItem("systemVoice",$("system-voice").checked?"1":"0");localStorage.setItem("voiceSel",String(Math.max(0,Math.min(9,parseInt($("voice-id").value,10)||0))));localStorage.setItem(STEPS_KEY,String(parseInt($("tts-quality").value,10)||6));speechSpeed=Math.max(.6,Math.min(1.2,parseFloat($("speech-speed").value)||.9));features={harakat:$("enable-harakat").checked,madd:$("enable-madd").checked,words:$("enable-words").checked,quiz:$("enable-quiz").checked};localStorage.setItem(KEY,v);localStorage.setItem(SPEED_KEY,speechSpeed);localStorage.setItem(LEARNING_VOLUME_KEY,String(learningVolume));pushNativePrefs();schedulePrefetch();localStorage.setItem(FEATURE_KEY,JSON.stringify(features));renderNumbers();renderLetterLessons();trMax=Math.max(0,parseInt($("tr-max").value,10)||0);trHelp=$("tr-help").checked;try{localStorage.setItem("trMax",String(trMax));localStorage.setItem("trHelp",trHelp?"1":"0")}catch(e){}$("settings").hidden=true;talk("تَمَّ حِفْظُ الإِعْدَادَات",.78)};
}
// ---- قفل الكبار: سؤال حسابي عشوائي، أو رمز سري يختاره الكبير. 3 أخطاء = قفل دقيقة ----
const PIN_KEY="parentPin";let gateOkFn=null,gateAns=0,gateBad=0,gateLockUntil=0;
function hashPin(p){let h=5381;for(const ch of String(p))h=((h<<5)+h+ch.charCodeAt(0))>>>0;return String(h)}
function getPin(){try{return localStorage.getItem(PIN_KEY)||""}catch(e){return ""}}
const toLatin=v=>String(v).replace(/[٠-٩]/g,d=>"٠١٢٣٤٥٦٧٨٩".indexOf(d)).trim();
function gateNew(){const inp=$("gate-in"),q=$("gate-q");inp.value="";
 if(getPin()){q.textContent="أدخل الرمز السري";inp.type="password";inp.placeholder="الرمز"}
 else{const a=7+Math.floor(Math.random()*3),b=13+Math.floor(Math.random()*7);gateAns=a*b;q.innerHTML='<span dir="ltr">'+a+" × "+b+" = ؟</span>";inp.type="text";inp.placeholder="الناتج"}}
function gateClose(){$("gate").hidden=true;gateOkFn=null;$("gate-in").value=""}
function askAdult(onOk){const left=Math.ceil((gateLockUntil-Date.now())/1000);
 $("gate").hidden=false;gateOkFn=onOk;
 if(left>0){$("gate-q").textContent="مقفل مؤقتًا";$("gate-msg").textContent="حاول بعد "+left+" ثانية";$("gate-in").disabled=true;$("gate-ok").disabled=true;return}
 $("gate-in").disabled=false;$("gate-ok").disabled=false;gateBad=0;$("gate-msg").textContent="";gateNew();setTimeout(()=>{try{$("gate-in").focus()}catch(e){}},60)}
function gateSubmit(){if(Date.now()<gateLockUntil)return;
 const v=toLatin($("gate-in").value),pin=getPin();
 const ok=pin?hashPin(v)===pin:(/^\d+$/.test(v)&&parseInt(v,10)===gateAns);
 if(ok){const f=gateOkFn;gateClose();gateBad=0;if(f)f();return}
 gateBad++;const box=$("gate").querySelector(".panel");box.classList.remove("gate-shake");void box.offsetWidth;box.classList.add("gate-shake");
 if(gateBad>=3){gateLockUntil=Date.now()+60000;gateBad=0;$("gate-in").disabled=true;$("gate-ok").disabled=true;$("gate-q").textContent="مقفل مؤقتًا";$("gate-msg").textContent="3 محاولات خاطئة. حاول بعد دقيقة.";return}
 $("gate-msg").textContent="إجابة غير صحيحة";gateNew()}
function pinSave(){const v=toLatin($("parent-pin").value);
 if(!/^\d{4,6}$/.test(v)){$("pin-msg").textContent="الرمز من 4 إلى 6 أرقام.";return}
 try{localStorage.setItem(PIN_KEY,hashPin(v))}catch(e){$("pin-msg").textContent="تعذّر الحفظ.";return}
 $("parent-pin").value="";$("pin-msg").textContent="تم حفظ الرمز. سيُطلب في كل مرة."}
function pinClear(){try{localStorage.removeItem(PIN_KEY)}catch(e){}$("parent-pin").value="";$("pin-msg").textContent="أُزيل الرمز. عاد السؤال الحسابي."}
function switchSettings(mode){["normal","advanced","training"].forEach(m=>{$("settings-"+m).classList.toggle("active",m===mode);$(m+"-settings").classList.toggle("active",m===mode)})}
window.onNativeListening=function(v){catSet("listen",v);$("tr-card").classList.toggle("listening",!!v&&trOn);if(v&&trOn&&!trLock)$("tr-status").textContent="قُلْ ما تراه 🎤";if(!v)$("tr-card").style.setProperty("--lvl",0);if(v){wake()}};
window.onNativeLevel=function(r){const l=Math.max(0,Math.min(1,(r+2)/12));$("tr-card").style.setProperty("--lvl",l.toFixed(2))};
window.onNativeSpeech=function(){}; // لا نصوص من كلام المستخدم تُحفظ أو تُعرض
window.onNativeSpeechError=function(){};
window.onNativeSession=function(active,msg){if(trOn&&!active)trStop(true,msg||"")};
window.__tpl="";window.onNativeTemplate=function(ok,k,info){
 if(!ok){window.__tpl=info;return}
 dbgShow("قوالب: "+info);
 const p=String(k).split(":"),it={t:p[0],i:parseInt(p[1],10)};
 if(!trOn||!trTarget||trLock||trKey(trTarget)!==k){muteMic(0);return}
 try{trHandle("",[it])}catch(e){muteMic(0)}};
window.onNativeHeard=function(t){dbgShow("سمع: «"+t+"»"+(window.__tpl?" — قوالب: "+window.__tpl:""));window.__tpl="";try{trHandle(t)}catch(e){muteMic(0);if(trOn&&!trLock)$("tr-status").textContent="قُلْ ما تراه 🎤"}}; // النص يُستعمل للمطابقة فقط ولا يُعرض ولا يُحفظ
window.onNativeTrainStatus=function(s){if(s==="empty")dbgShow("لم يفهم شيئًا (النص فارغ)");if(s==="empty")s="idle";if(trOn&&s==="prep")$("tr-status").textContent="جارٍ تجهيز التعرف على الصوت… لحظات ⏳";if(trOn&&s==="think"&&!trLock)$("tr-status").textContent="⏳ لحظة…";if(trOn&&s==="idle"&&!trLock)$("tr-status").textContent="قُلْ ما تراه 🎤"};
window.onNativeToast=function(m){if(document.body.classList.contains("lessons-on"))showSpeech(m);if(trOn&&!trLock)$("tr-status").textContent=m};
window.onNativeEvent=function(t,p){
 if(t==="ttsready"){pushNativePrefs();schedulePrefetch();return}
 if(t==="speaking"){if(trOn&&p==="0")muteMic(120);if(trOn&&trAfter){if(p==="1")trSpoke=true;else if(trSpoke){const a=trAfter;trAfter=null;clearTimeout(trTimer);setTimeout(a.f,a.d)}}clearTimeout(talkDog);catSet("talk",p==="1");if(p==="1"){catSet("listen",false);wake()}else{catOnce("happy",600)}}
 
};

// تطبيق فوري: كل تغيير في الإعدادات الصوتية يُحفَظ ويُرسَل للتطبيق مباشرة، ثم يمكن سماعه بزر «جرّب الصوت»
function applyLive(){
 speechSpeed=Math.max(.6,Math.min(1.2,parseFloat($("speech-speed").value)||.9));
 $("speech-speed-value").textContent=speechSpeed.toFixed(2);
  localStorage.setItem(SPEED_KEY,speechSpeed);
 localStorage.setItem("systemVoice",$("system-voice").checked?"1":"0");
 localStorage.setItem("voiceSel",String(Math.max(0,Math.min(9,parseInt($("voice-id").value,10)||0))));
 localStorage.setItem(STEPS_KEY,String(parseInt($("tts-quality").value,10)||6));
 pushNativePrefs();
}
["speech-speed","voice-id","tts-quality","system-voice"].forEach(id=>{const e=$(id);if(!e)return;
 const f=()=>{applyLive();if(id==="speech-speed"||id==="voice-id"||id==="tts-quality")schedulePrefetch()};
 e.addEventListener("input",f);e.addEventListener("change",f)});
// ---- حساسية الالتقاط: تُحفظ وتُرسل للتطبيق فورًا ----
[["asr-sens","asrSens"],["asr-minv","asrMinV"],["asr-vowel","asrVowel"],["asr-end","asrEnd"],["asr-max","asrMax"],["asr-start","asrStart"]].forEach(x=>{const e=$(x[0]);const f=()=>{try{localStorage.setItem(x[1],String(parseInt(e.value,10)))}catch(_){}asrLabels();pushNativePrefs()};e.addEventListener("input",f);e.addEventListener("change",f)});
$("tr-debug").checked=dbgOn();$("tr-debug").addEventListener("change",()=>{try{localStorage.setItem("trDebug",$("tr-debug").checked?"1":"0")}catch(_){}if(!$("tr-debug").checked)$("tr-dbg").hidden=true});
$("asr-engine").addEventListener("change",()=>{try{localStorage.setItem("asrEngine",$("asr-engine").value)}catch(_){}pushNativePrefs()});
// ---- قوالب الصوت: تسجيل الكبار ----
const EN_TAKES=2,EN_MAX=6;let enItems=[],enIdx=0,enBusy=false,enSt={},enCat="n",enPlaying=0,enCountT=0,enConf={},enTest={};
function enBuild(){enItems=[];for(let n=1;n<=10;n++)enItems.push({t:"n",i:n});colors.forEach((_,i)=>enItems.push({t:"c",i:i}));letters.forEach((_,i)=>enItems.push({t:"l",i:i}))}
function enStatus(){try{enSt=JSON.parse(TomNative.templateStatus()||"{}")}catch(e){enSt={}}return enSt}
function tplRefresh(){if(!enItems.length)enBuild();const st=enStatus();let d=0;enItems.forEach(it=>{if((st[trKey(it)]||0)>=EN_TAKES)d++});$("tpl-status").textContent="سُجّل "+d+" من "+enItems.length+" عنصرًا"+(()=>{const m=(st.synthKeys||[]).filter(k=>!(st[k]>0)).length;return m?" · "+m+" بنموذج صوت القط":""})()+(st.auto?" · تعلّم من الطفل "+st.auto+" بصمة":"")}
function enFace(it){return it.t==="n"?String(it.i):it.t==="l"?letters[it.i][0]:'<span class="en-sw" style="background:'+colors[it.i][1]+'"></span>'}
function enNameOf(k){const p=k.split(":");return trSay({t:p[0],i:parseInt(p[1],10)})}
function enModel(k){return (enSt.synthKeys||[]).indexOf(k)>=0}
function pushModelTexts(){try{if(!window.TomNative||!TomNative.setModelTexts)return;if(!enItems.length)enBuild();const o={};enItems.forEach(it=>{o[trKey(it)]=trSay(it)});TomNative.setModelTexts(JSON.stringify(o))}catch(e){}}
function enCatList(){return enItems.map((it,i)=>i).filter(i=>enItems[i].t===enCat)}
function enGrid(){enStatus();const g=$("en-grid");g.innerHTML="";g.className="en-grid en-grid-"+enCat;
 enCatList().forEach(i=>{const it=enItems[i],n=enSt[trKey(it)]||0,b=document.createElement("button");b.type="button";b.className="en-cell"+(n>=EN_TAKES?" done":n>0?" part":enModel(trKey(it))?" model":"");
  b.innerHTML='<span class="en-face">'+enFace(it)+'</span><span class="en-badge">'+(n>=EN_TAKES?"✓ ":n===0&&enModel(trKey(it))?"🐱 ":"")+n+'</span>';b.onclick=()=>enSelect(i);g.appendChild(b)});
 ["n","c","l"].forEach(c=>$("en-cat-"+c).classList.toggle("active",c===enCat))}
function enPlayStop(){try{TomNative.enrollPlayStop&&TomNative.enrollPlayStop()}catch(e){}enPlaying=0}
function enCancelCount(){if(enCountT){clearTimeout(enCountT);enCountT=0}const c=$("en-count");if(c)c.textContent=""}
function enSelect(i){enIdx=i;$("en-list").hidden=true;$("en-detail").hidden=false;enShow()}
function enBack(){enPlayStop();enCancelCount();$("en-detail").hidden=true;$("en-list").hidden=false;enGrid()}
function enTakes(k){try{return JSON.parse(TomNative.templateTakes(k)||"[]")}catch(e){return[]}}
function enArm(b,txt,fn){let armed=0;b.onclick=()=>{if(!armed){armed=setTimeout(()=>{armed=0;b.textContent=txt},3500);b.textContent="اضغط مرة أخرى للتأكيد";return}clearTimeout(armed);armed=0;b.textContent=txt;fn()}}
function enNotes(k){const w=$("en-warn"),t=$("en-test"),c=enConf[k],r=enTest[k];
 if(c&&c.own!==undefined){let m="",bad=false;const nm=c.key?enNameOf(c.key):"";
  if(c.own<0)m="سجّل نطقًا ثانيًا لهذا العنصر ليتمكن التطبيق من فحص التشابه.";
  else if(c.warn){bad=true;m="⚠️ نطقك قريب من «"+nm+"» (نسبة التمييز "+c.ratio.toFixed(2)+"، المطلوب "+(c.th||1.25).toFixed(2)+" فأكثر). إن كان نطقًا صحيحًا آخر للعنصر فلا بأس، وإلا فاحذفه وأعد التسجيل بوضوح أكبر وأقرب للميكروفون."}
  else m="✓ واضح ومختلف عن باقي العناصر (أقرب عنصر: «"+nm+"»، نسبة "+c.ratio.toFixed(2)+").";
  w.hidden=false;w.className="en-note "+(bad?"en-warn":"en-good");w.textContent=m}else w.hidden=true;
 if(r){t.hidden=false;let m="",good=false;
  if(r.silent)m="لم أسمع صوتًا واضحًا، حاول مرة أخرى بصوت أعلى.";
  else if(!r.ready)m=/غير كافية/.test(r.info)?"الاختبار يحتاج نطقين (أو نموذج القط) لخمسة عناصر مختلفة على الأقل.":"سجّل نطقين لهذا العنصر أولًا، أو انتظر حتى يجهز نموذج القط.";
  else if(r.ok){good=true;m="✅ قبله القط كـ«"+enNameOf(k)+"» (نسبة "+r.ratio.toFixed(2)+"، كلما زادت كان التمييز أوضح)"+(r.synth?" — بنموذج صوت القط.":".")}
  else if(r.oKey&&r.other<r.dt)m="❌ ظنّه القط أقرب إلى «"+enNameOf(r.oKey)+"». أعد تسجيل العنصر أو أضف نطقًا آخر.";
  else m="❌ لم يقبله القط (نسبة "+r.ratio.toFixed(2)+"). جرّب نطقًا أوضح أو أضف نطقًا آخر.";
  t.className="en-note "+(good?"en-good":"en-warn");t.textContent=m}else t.hidden=true}
function enShow(msg){enStatus();const it=enItems[enIdx],k=trKey(it),tk=enTakes(k),busy=enBusy||!!enCountT,mdl=enModel(k);enPlayStop();
 $("en-item").innerHTML=enFace(it);$("en-name").textContent="قُل: "+trSay(it);
 const pos=enCatList().indexOf(enIdx)+1;$("en-prog").textContent="العنصر "+pos+" من "+enCatList().length+" · الأنطاق المسجّلة: "+tk.length+" من "+EN_MAX+(tk.length<EN_TAKES?" (سجّل "+EN_TAKES+" على الأقل)":"");
 const box=$("en-takes");box.innerHTML="";
 tk.forEach((t,n)=>{const row=document.createElement("div");row.className="en-take";
  const lab=document.createElement("span");lab.className="en-take-n";lab.textContent="نطق "+(n+1);row.appendChild(lab);
  const pb=document.createElement("button");pb.type="button";pb.className="en-play";pb.textContent=t.a?"▶ معاينة":"— بلا معاينة";pb.disabled=!t.a||busy;
  pb.onclick=()=>{if(enPlaying===t.g){enPlayStop();enShow();return}enPlayStop();enPlaying=t.g;try{TomNative.enrollPlay(k,t.g)}catch(e){}pb.textContent="⏹ إيقاف"};row.appendChild(pb);
  const db=document.createElement("button");db.type="button";db.className="en-del";db.textContent="🗑️";db.disabled=busy;enArm(db,"🗑️",()=>{enPlayStop();try{TomNative.enrollDelete(k,t.g)}catch(e){}delete enConf[k];delete enTest[k];enShow("حُذف النطق. يمكنك تسجيل نطق جديد.")});row.appendChild(db);
  box.appendChild(row)});
 if(!tk.length){const e=document.createElement("p");e.className="note en-empty";e.textContent=mdl?"🐱 لا تسجيلات بعد؛ يعتمد القط على صوت نطقه كنموذج (أقل دقة من تسجيلك). سجّل نطقك لتحسين الدقة.":"لا توجد تسجيلات لهذا العنصر بعد.";box.appendChild(e)}
 const full=tk.length>=EN_MAX;$("en-rec").disabled=busy||full;$("en-rec").textContent=full?"وصلت للحد الأقصى — احذف نطقًا لتسجيل غيره":tk.length?"🎤 سجّل نطقًا آخر":"🎤 سجّل";
 $("en-try").disabled=busy||(!tk.length&&!mdl);$("en-chk").disabled=busy||!tk.length;
 $("en-meter").hidden=!enBusy;
 $("en-msg").textContent=msg||"اضغط «سجّل» ثم انطق بوضوح؛ يتوقف التسجيل تلقائيًا بعد انتهائك.";enNotes(k);
 const L=enCatList(),p=L.indexOf(enIdx);$("en-prev").disabled=p<=0||busy;$("en-next").disabled=p>=L.length-1||busy;
 const da=$("en-del-all");da.disabled=!tk.length||busy;enArm(da,"🗑️ حذف كل تسجيلات هذا العنصر",()=>{enPlayStop();try{TomNative.enrollDelete(k,0)}catch(e){}delete enConf[k];delete enTest[k];enShow("حُذفت كل تسجيلات هذا العنصر. سجّله من جديد.")})}
function enStart(mode){if(enBusy||enCountT)return;const k=trKey(enItems[enIdx]);let n=3;delete enTest[k];
 const tick=()=>{if(n>0){$("en-count").textContent=String(n);$("en-msg").textContent=mode==="test"?"استعد للاختبار…":"استعد…";n--;enCountT=setTimeout(tick,700);return}
  enCountT=0;$("en-count").textContent="";try{if(mode==="test")TomNative.enrollTest(k);else TomNative.enrollRecord(k)}catch(e){enShow("تعذّر التسجيل.")}};
 enShow("استعد…");tick();$("en-rec").disabled=true;$("en-try").disabled=true;$("en-chk").disabled=true}
function enOpen(){if(!window.TomNative||!TomNative.enrollRecord){$("tpl-status").textContent="التسجيل يعمل داخل التطبيق فقط.";return}
 if(trOn)trStop(false);if(!enItems.length)enBuild();$("enroll").hidden=false;$("en-detail").hidden=true;$("en-list").hidden=false;enGrid()}
function enStep(d){const L=enCatList(),p=L.indexOf(enIdx)+d;if(p<0||p>=L.length||enBusy||enCountT)return;enIdx=L[p];enShow()}
window.onNativePlay=function(on){enPlaying=0;if(!$("en-detail").hidden)enShow($("en-msg").textContent)};
window.onNativeLevel2=function(v){const i=$("en-meter-i");if(i)i.style.width=Math.round(Math.max(0,Math.min(1,v))*100)+"%"};
window.onNativeConfusion=function(k,c){enConf[k]=c;if(!$("en-detail").hidden&&trKey(enItems[enIdx])===k)enNotes(k)};
window.onNativeTest=function(k,r){enBusy=false;enTest[k]=r;if(!$("en-detail").hidden)enShow("انتهى الاختبار.")};
window.onNativeEnroll=function(k,st,takes){
 if(st==="rec"){enBusy=true;enPlayStop();enShow("🔴 تكلّم الآن… (يتوقف تلقائيًا بعد أن تنتهي)");return}
 enBusy=false;
 if(st==="ok"){delete enConf[k];enShow("✓ تم حفظ النطق. عاينه للتأكد، أو سجّل نطقًا آخر.")}
 else if(st==="weak")enShow("الصوت خافت أو قصير، أعد التسجيل.");
 else if(st==="perm")enShow("اسمح بالميكروفون ثم اضغط «سجّل» مرة أخرى.");
 else enShow("تعذّر التسجيل، حاول مرة أخرى.")};
$("tpl-record").onclick=enOpen;$("en-close").onclick=()=>{enPlayStop();enCancelCount();$("enroll").hidden=true;tplRefresh()};
$("en-back").onclick=()=>{if(!enBusy)enBack()};
["n","c","l"].forEach(c=>{$("en-cat-"+c).onclick=()=>{enCat=c;enGrid()}});
$("en-rec").onclick=()=>enStart("rec");$("en-try").onclick=()=>enStart("test");
$("en-chk").onclick=()=>{const k=trKey(enItems[enIdx]);$("en-warn").hidden=false;$("en-warn").className="en-note";$("en-warn").textContent="جارٍ الفحص…";try{TomNative.enrollCheck(k)}catch(e){}};
$("en-prev").onclick=()=>enStep(-1);$("en-next").onclick=()=>enStep(1);
window.onNativeBackup=function(kind,ok,msg){$("bk-msg").textContent=(ok?"✓ ":"✗ ")+msg;if(ok&&kind==="import")tplRefresh()};
$("bk-export").onclick=()=>{$("bk-msg").textContent="";try{TomNative.backupExport()}catch(e){$("bk-msg").textContent="التصدير يعمل داخل التطبيق فقط."}};
enArm($("bk-import"),"📥 استيراد",()=>{$("bk-msg").textContent="";try{TomNative.backupImport()}catch(e){$("bk-msg").textContent="الاستيراد يعمل داخل التطبيق فقط."}});
{const m=$("tpl-model");m.checked=localStorage.getItem("tplModel")!=="0";m.addEventListener("change",()=>{try{localStorage.setItem("tplModel",m.checked?"1":"0")}catch(_){}pushNativePrefs();setTimeout(tplRefresh,1500)})}
{const w=$("tpl-warn"),o=$("tpl-warn-value"),sh=()=>{o.textContent=parseFloat(w.value).toFixed(2)};w.value=localStorage.getItem("tplWarn")||"1.25";sh();const f=()=>{sh();try{localStorage.setItem("tplWarn",String(parseFloat(w.value)))}catch(_){}pushNativePrefs()};w.addEventListener("input",f);w.addEventListener("change",f)}
setTimeout(()=>{pushModelTexts();pushNativePrefs()},800);
$("tpl-auto").checked=localStorage.getItem("tplAuto")!=="0";$("tpl-strict").value=localStorage.getItem("tplStrict")||"1";$("tpl-strict").addEventListener("change",()=>{try{localStorage.setItem("tplStrict",$("tpl-strict").value)}catch(_){}pushNativePrefs()});
$("tpl-auto").addEventListener("change",()=>{try{localStorage.setItem("tplAuto",$("tpl-auto").checked?"1":"0")}catch(_){}pushNativePrefs()});
[["tpl-clear-auto",true,"🗑️ مسح ما تعلّمه من الطفل"],["tpl-clear-all",false,"🗑️ مسح كل القوالب"]].forEach(x=>{const b=$(x[0]);let armed=0;b.onclick=()=>{if(!armed){armed=setTimeout(()=>{armed=0;b.textContent=x[2]},4000);b.textContent="اضغط مرة أخرى للتأكيد";return}clearTimeout(armed);armed=0;b.textContent=x[2];try{TomNative.templatesClear(x[1])}catch(e){}tplRefresh()}});
// ---- زر «↺ افتراضي» تحت كل إعداد، وزر لإعادة الكل ----
const SET_DEF={"speech-speed":"0.9","voice-id":"9","tts-quality":"6","system-voice":false,"learning-volume-level":"100","max-number":"10","enable-harakat":false,"enable-madd":false,"enable-words":false,"enable-quiz":false,"tr-max":"3","tr-help":true,"asr-engine":"base","asr-sens":"1","asr-minv":"300","asr-vowel":"150","asr-end":"300","asr-max":"4","asr-start":"200","tpl-warn":"1.25","tpl-model":true};
function resetOne(id){const e=$(id);if(!e)return;if(e.type==="checkbox")e.checked=!!SET_DEF[id];else e.value=SET_DEF[id];e.dispatchEvent(new Event("input",{bubbles:true}));e.dispatchEvent(new Event("change",{bubbles:true}))}
Object.keys(SET_DEF).forEach(id=>{const e=$(id);if(!e)return;const lab=e.closest("label");if(!lab)return;const b=document.createElement("button");b.type="button";b.className="rst";b.textContent="↺ افتراضي";b.onclick=()=>resetOne(id);lab.insertAdjacentElement("afterend",b)});
$("reset-all").onclick=()=>{Object.keys(SET_DEF).forEach(resetOne);$("save-settings").onclick()};
$("test-voice").onclick=()=>{applyLive();talk("مَرْحَبًا أَنَا القِطُّ المُتَكَلِّم",speechSpeed)};
renderNumbers();renderLetters();renderColors();bind();bindCat($("cat2"));bindCat($("cat3"));startIdle();setMode("training",true);
})();
