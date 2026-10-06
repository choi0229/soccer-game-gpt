export type School = { id:string; name:string; region:number; type:string; typeLabel:string; power:number };
export type Menu = {id:string; label:string; stats:string[]};
export type Event = {id:string; axis:string; title:string; body:string; choices:{text:string}[]};
export type Config = {schools:School[]; menus:Menu[]; rules:Record<string,unknown>};
export type Moment = {minute:number; text:string; success:boolean; chance:number};
export type Match = {day:number; competition:string; home:string; away:string; homeGoals:number; awayGoals:number; role:string; rating:number|null; goals:number; assists:number; winner:string|null; moments:Moment[]};
export type Standing = {schoolId:string; played:number; won:number; drawn:number; lost:number; goalsFor:number; goalsAgainst:number; points:number};
export type Attribute = {id:string; label:string; value:number; grade:string; primary:boolean; trainable:boolean; change:number};
export type Slot = {key:string; label:string; value:string; options:{id:string; label:string}[]; fixed:string|null};
export type Run = {
 totalWeeks:number; statMax:number; id:string; seed:string; date:string; vacation:boolean; weekday:number; week:number; maxStamina:number; conditionLabel:string; injured:boolean; injuryDaysRemaining:number; school:School; leagueRank:number; matchDay:boolean; config:Config; standings:Standing[]; event:Event|null; slots:Slot[]; attributes:Attribute[];
 state:{seq:number; day:number; completed:boolean; stamina:number; academic:number; money:number; reputation:number; affinity:Record<string,number>; selections:Record<string,string>; report:string[]; matches:Match[]; todayMatches:Match[]; trainingCounts:Record<string,number>; winterSupplementRequired:boolean; supplementRequired:boolean};
 records:{games:number; appearances:number; goals:number; assists:number; rating:number; injuries:number; exclusions:number; champion:string|null};
};
export type Action = {kind:'day'|'event'; changes?:Record<string,string>; sundayAction?:string; choice?:number};
