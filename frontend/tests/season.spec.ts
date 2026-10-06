import {test,expect,type Page} from '@playwright/test';
import type {Run} from '../src/types';
test('play a complete first-year season, reload and replay',async({page})=>{
 const errors:string[]=[];page.on('pageerror',error=>errors.push(error.message));
 await page.goto('/');await expect(page.getByRole('heading',{name:'1학년 시즌 시작'})).toBeVisible();
 await page.getByLabel('시즌 시드').fill('2026');
 const start=page.waitForResponse(r=>r.url().endsWith('/api/runs')&&r.request().method()==='POST');
 await page.getByRole('button',{name:'운동부에 입부하기'}).click();let run:Run=await(await start).json();
 let eventCount=0,matchCount=0;
 while(!run.state.completed){
  await expect(page.locator('.dashboard')).toHaveAttribute('data-seq',String(run.state.seq));
  if(run.event){
   await expect(page.locator('.event-choices')).toBeVisible();const next=page.waitForResponse(r=>r.url().endsWith('/actions')&&r.request().method()==='POST');
   await page.locator('.event-choices button').first().click();run=await(await next).json();eventCount++;continue;
  }
  if(run.state.todayMatches.length>0&&await page.locator('.match-modal').count()){
   await expect(page.locator('.match-modal')).toBeVisible();
   if(await page.getByRole('button',{name:'중계 건너뛰기'}).count())await page.getByRole('button',{name:'중계 건너뛰기'}).click();
   await page.getByRole('button',{name:'경기 확인 완료'}).click();await expect(page.locator('.match-modal')).toHaveCount(0);matchCount++;
  }
  const next=page.waitForResponse(r=>r.url().endsWith('/actions')&&r.request().method()==='POST');
  await page.locator('.routine-footer .primary').click();const response=await next;expect(response.status()).toBe(200);run=await response.json();
 }
 await expect(page.getByRole('heading',{name:'48주, 첫 시즌을 마쳤습니다.'})).toBeVisible();
 expect(run.state.day).toBe(336);expect(eventCount).toBeGreaterThan(0);expect(matchCount).toBeGreaterThanOrEqual(14);
 await page.reload();await expect(page.getByRole('heading',{name:'48주, 첫 시즌을 마쳤습니다.'})).toBeVisible();
 await page.getByRole('button',{name:'선택 기록 재현 확인'}).click();await expect(page.getByRole('status')).toContainText('재현 결과가 일치합니다');
 await page.getByRole('button',{name:'리그',exact:true}).click();await expect(page.locator('tbody tr')).toHaveCount(8);
 expect(errors).toEqual([]);
});
test('small viewport has usable daily controls',async({page})=>{
 await page.setViewportSize({width:390,height:844});await page.goto('/');
 const start=page.waitForResponse(r=>r.url().endsWith('/api/runs')&&r.request().method()==='POST');
 await page.getByRole('button',{name:'운동부에 입부하기'}).click();await start;
 await expect(page.getByLabel('야간 · 개인 훈련')).toBeVisible();
 const overflow=await page.evaluate(()=>document.documentElement.scrollWidth>window.innerWidth);expect(overflow).toBe(false);
});
