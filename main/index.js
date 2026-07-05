const core = require('@actions/core');
const { exec } = require('child_process');
const fs = require('fs');
const path = require('path');

async function validateWrappers() {
    const wrapperPath = path.join(process.cwd(), 'gradle', 'wrapper', 'gradle-wrapper.jar');
    
    // Check if the Gradle Wrapper JAR file exists
    if (!fs.existsSync(wrapperPath)) {
        throw new Error(`Gradle Wrapper JAR file not found at ${wrapperPath}`);
    }

    // Validate the Gradle Wrapper JAR file
    const expectedHash = 'a5e75118d96b4eac2100876f6af6a5ca5029cd440f87736425350fb4cf308b42'; // Update this to the correct hash
    const actualHash = await getFileHash(wrapperPath);

    if (actualHash !== expectedHash) {
        throw new Error(`Found unknown Gradle Wrapper JAR files: ${actualHash} ${wrapperPath}`);
    }
}

async function getFileHash(filePath) {
    return new Promise((resolve, reject) => {
        exec(`shasum -a 256 ${filePath}`, (error, stdout) => {
            if (error) {
                return reject(error);
            }
            resolve(stdout.split(' ')[0]);
        });
    });
}

async function run() {
    try {
        await validateWrappers();
        core.info('Gradle Wrapper validation passed.');
    } catch (error) {
        core.setFailed(error.message);
    }
}

run();