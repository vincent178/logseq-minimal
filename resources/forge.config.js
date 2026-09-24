const path = require('path')
const fs = require('fs')

module.exports = {
  packagerConfig: {
    name: 'Logseq Minimal',
    icon: './icons/logseq_big_sur.icns',
    buildVersion: "88",
    appBundleId: "com.logseq.minimal",
    protocols: [
      {
        "protocol": "logseq",
        "name": "logseq",
        "schemes": "logseq"
      }
    ],
    // Unsigned local build: no Apple Developer cert available on the build
    // machine. Users bypass Gatekeeper via right-click → Open.
    osxSign: undefined,
    osxNotarize: undefined,
  },
  makers: [
    {
      'name': '@electron-forge/maker-squirrel',
      'config': {
        'name': 'LogseqMinimal',
        'setupIcon': './icons/logseq.ico',
        'loadingGif': './icons/installing.gif',
        'certificateFile': process.env.CODE_SIGN_CERTIFICATE_FILE,
        'certificatePassword': process.env.CODE_SIGN_CERTIFICATE_PASSWORD,
        "rfc3161TimeStampServer": "http://timestamp.digicert.com"
      }
    },
    {
      'name': '@electron-forge/maker-wix',
      'config': {
        name: 'LogseqMinimal',
        icon: path.join(__dirname, './icons/logseq.ico'),
        language: 1033,
        manufacturer: 'Logseq',
        appUserModelId: 'com.logseq.minimal',
        upgradeCode: "3778eb84-a0ce-4109-9120-5d4315e0d7cf",
        ui: {
          enabled: false,
          chooseDirectory: true,
          images: {
            banner: path.join(__dirname, './windows/banner.jpg'),
            background: path.join(__dirname, './windows/background.jpg')
          },
        },
        // Standard WiX template appends the unsightly "(Machine - WSI)" to the name, so use our own template
        beforeCreate: (msiCreator) => {
          return new Promise((resolve, reject) => {
            fs.readFile(path.join(__dirname,"./windows/wix.xml"), "utf8" , (err, content) => {
                if (err) {
                    reject (err);
                }
                msiCreator.wixTemplate = content;
                resolve();
            });
          });
        }
      }
    },
    {
      name: '@electron-forge/maker-dmg',
      config: {
        format: 'ULFO',
        icon: './icons/logseq_big_sur.icns',
        name: 'Logseq Minimal'
      }
    },
    {
      name: '@electron-forge/maker-zip',
      platforms: ['darwin', 'linux', 'win32'],
    },

    {
      name: 'electron-forge-maker-appimage',
      platforms: ['linux'],
      config: {
        mimeType: ["x-scheme-handler/logseq"]
      }
    }
  ],

  publishers: [
    {
      name: '@electron-forge/publisher-github',
      config: {
        repository: {
          owner: 'vincent178',
          name: 'logseq-minimal'
        },
        prerelease: true
      }
    }
  ]
}
