(()=>{'use strict';
const key='spaceagent-prototype-theme';
let theme=localStorage.getItem(key);
if(theme!=='light'&&theme!=='dark')theme=matchMedia('(prefers-color-scheme: light)').matches?'light':'dark';
function apply(){document.documentElement.dataset.theme=theme;document.documentElement.style.colorScheme=theme;const meta=document.querySelector('meta[name="theme-color"]');if(meta)meta.content=theme==='dark'?'#080a0c':'#f2f3f0';const button=document.getElementById('theme-button');if(button){button.textContent=theme==='dark'?'☀':'☾';button.setAttribute('aria-label',theme==='dark'?'Switch to light mode':'Switch to dark mode');button.title=button.getAttribute('aria-label')}}
function mount(){const languageButton=document.getElementById('language-button');if(!languageButton||document.getElementById('theme-button')){apply();return}if(languageButton.parentElement?.classList.contains('topbar')){const group=document.createElement('div');group.className='top-actions';languageButton.parentElement.insertBefore(group,languageButton);group.append(languageButton)}const button=document.createElement('button');button.id='theme-button';button.type='button';button.className=`${languageButton.className||'top-button'} theme-toggle`.trim();languageButton.insertAdjacentElement('afterend',button);button.addEventListener('click',()=>{theme=theme==='dark'?'light':'dark';localStorage.setItem(key,theme);apply()});apply()}
apply();if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',mount,{once:true});else mount();
})();
