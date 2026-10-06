import {create} from 'zustand';
type Ui = {runId:string|null; changes:Record<string,string>; sunday:string; tab:'stats'|'league'|'matches'; seenMatchSeq:number; visibleScenes:number; seed:string; schoolId:string; replayOpen:boolean; set:(patch:Partial<Ui>)=>void};
export const useUi=create<Ui>((set)=>({runId:localStorage.getItem('soccer-run'),changes:{},sunday:'rest',tab:'stats',seenMatchSeq:0,visibleScenes:0,seed:'2026',schoolId:'',replayOpen:false,set}));
export function selectRun(id:string|null) { if(id) localStorage.setItem('soccer-run',id);else localStorage.removeItem('soccer-run');useUi.getState().set({runId:id,changes:{},seenMatchSeq:0,visibleScenes:0,replayOpen:false}); }
