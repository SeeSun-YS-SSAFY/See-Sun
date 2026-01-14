import type { Meta, StoryObj } from '@storybook/nextjs-vite'
import MicButton from "./MicButton";

const meta = {
    title: 'Common/MicButton',
    component: MicButton,
    parameters: {
        layout: 'centered'
    },
    argTypes: {
        status: {
            control: 'radio',
            options: ['off', 'recording']
        }
    }
} satisfies Meta<typeof MicButton>;

export default meta;
type Story = StoryObj<typeof meta>

export const Off: Story = {
    args: {
        status: 'off'
    }
}

export const Recording: Story = {
    args: {
        status: 'recording',
    }
}
