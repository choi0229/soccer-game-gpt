import {defineConfig} from '@playwright/test';
export default defineConfig({testDir:'./tests',timeout:180_000,use:{baseURL:process.env.BASE_URL??'http://localhost:5187',headless:true},reporter:'list'});
