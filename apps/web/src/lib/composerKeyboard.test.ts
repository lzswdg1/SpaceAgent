import {describe,it,expect} from 'vitest'
import {shouldSubmitComposer} from './composerKeyboard'
describe('composer Enter',()=>{
  it('submits only a non-composing unmodified Enter',()=>{
    const event={key:'Enter',shiftKey:false,nativeEvent:{isComposing:false,keyCode:13}}
    expect(shouldSubmitComposer(event)).toBe(true)
    expect(shouldSubmitComposer({...event,shiftKey:true})).toBe(false)
    expect(shouldSubmitComposer({...event,key:'a'})).toBe(false)
    expect(shouldSubmitComposer({...event,nativeEvent:{isComposing:true,keyCode:13}})).toBe(false)
    expect(shouldSubmitComposer({...event,nativeEvent:{isComposing:false,keyCode:229}})).toBe(false)
  })
})
