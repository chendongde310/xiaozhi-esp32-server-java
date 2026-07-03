import type { CapacitorConfig } from '@capacitor/cli'

const config: CapacitorConfig = {
  appId: 'com.xiaozhi.happyplanet',
  appName: '快乐星球',
  webDir: 'dist',
  server: {
    androidScheme: 'http',
    cleartext: true,
  },
  android: {
    allowMixedContent: true,
  },
}

export default config
